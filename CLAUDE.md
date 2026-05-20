# Notes for AI assistants

This file is a map of the repository for AI coding agents (Claude Code,
Copilot, Cursor, etc.). Humans should read [README.md](README.md) first.

## What this project is

A Java library that decorates `javax.xml.stream.XMLEventReader` so that
an annotated XML template expands into generated test data on the fly.
The public entry point is `org.brylex.xmlgen.GeneratingXMLEventReader`.

Four template directives are supported, all in namespace `urn:xml:gen`:

- `gen:repeat="N"` (attribute) — repeat the element subtree N times
- `gen:increment="N"` (attribute) — add N to the integer text content
  (accumulates per repeat iteration when nested inside `gen:repeat`)
- `gen:pick="pool/column"` (attribute) — replace the element's text
  with a value from a named pool of records supplied at reader
  construction (`Pools`). Picks against the same pool within one
  `gen:repeat` iteration share a row.
- `<gen:choose>` / `<gen:when weight="N">` (elements) — emit exactly
  one branch, weighted-randomly. Inside a `gen:repeat`, each iteration
  re-picks. Pass a seeded `Random` to the reader's three-arg
  constructor for reproducibility.
- `gen:random-int="min..max"`, `gen:random-amount="min..max"`,
  `gen:random-date="YYYY-MM-DD..YYYY-MM-DD"` (attributes) — replace the
  element's text with a random value in the closed range. Implemented
  by `RandomXMLEventReader`, which sits between `TextProcessingXML…`
  and `PoolPickXMLEventReader` in the decorator chain. Shares the same
  `Random` instance as `gen:choose`, so a single seed reproduces an
  entire run.

## Architecture

The reader is a chain of decorators, wired in
`GeneratingXMLEventReader`'s constructor:

```
caller
  └── GeneratingXMLEventReader
        └── DelegatingXMLEventReader            // dispatches to recorders
              └── TextProcessingXMLEventReader      // applies gen:increment
                    └── RandomXMLEventReader            // applies gen:random-*
                          └── PoolPickXMLEventReader      // applies gen:pick (when Pools supplied)
                                └── StackXMLEventReader     // lookahead/replay stack
                                      └── source XMLEventReader (template)
```

`RecordingXMLEventReader` captures the events inside a `gen:repeat`
subtree and replays them N times onto the `StackXMLEventReader`'s stack
on completion. `GeneratorStrippingStartEvent` removes `gen:*` attributes
from `StartElement` events before they reach the caller. `_XMLEvent`
preserves all `gen:*` attributes on the wrapped `StartElement` so that
downstream decorators (e.g., `PoolPickXMLEventReader`) can still see them
after the recording's `decrementRepeat` round-trip.

`PoolPickXMLEventReader` keeps a stack of pin frames. Each
`gen:repeat`-tagged StartElement pushes a new frame; the matching
EndElement (detected via depth tracking) pops it. A pick walks the
stack top-down, returning the first existing pin for the pool, or
pinning a fresh row in the top frame if none. This is what gives
nested `gen:repeat`s coherent semantics (outer customer pin survives
across inner line iterations, inner product pin refreshes per line).
The detection relies on `_XMLEvent.decrementRepeat()` preserving the
`gen:repeat` attribute even on the last iteration (with value 1), so
PoolPick sees a consistent iteration-boundary marker on every pass.

`gen:choose` is handled inline in `GeneratingXMLEventReader.nextEvent()`:
when the materialize step sees `<gen:choose>`, `handleChoose()` pulls
events through `delegate.nextEvent()` (so any active gen:repeat recorder
still records the full structure), groups them into branches by
`<gen:when>` boundaries, picks one weighted-randomly, and queues the
chosen events in a `pending` deque. The next `nextEvent()` / `peek()`
call drains `pending` through `DelegatingXMLEventReader.feedEvent()`,
which routes events into any newly-pushed inner recorder (created when
the chosen branch itself contains `gen:repeat`). Depth-tracking via
`pendingOuterDepth` prevents double-recording into the outer recorder
that already captured the full choose subtree during `handleChoose()`'s
pull-loop. This makes `gen:repeat` inside `gen:when` first-class.

## Inference pipeline

`org.brylex.xmlgen.infer` is a separate pipeline from the expansion
chain. Given 5–50 real XML samples, it emits an annotated template +
`Pools` that round-trips through `GeneratingXMLEventReader`:

```
TemplateInferrer.infer(samples)
  └── ShapeBuilder              // XMLEventReaders → ShapeNode tree
  └── AnalyzerPipeline          // decorates nodes with directive decisions
        ├── RepeatAnalyzer         // gen:repeat from sibling cardinality
        ├── IncrementAnalyzer      // gen:increment from monotonic sequences
        ├── RandomRangeAnalyzer    // gen:random-* (int/amount/date/uuid)
        ├── ChooseAnalyzer         // gen:choose from signature groups
        └── PickCoherenceAnalyzer  // gen:pick from bijective low-card leaves
  └── TemplateRenderer          // ShapeNode tree → annotated XML + Pools
  └── InferredTemplate (xml, pools, warnings)
```

Pipeline is deterministic: same samples → byte-identical template,
regardless of input ordering. Cross-sample child order is canonicalized
via frequency-mode with lex tiebreak in `ShapeBuilder`; pool rows are
lex-sorted on first column in `TemplateRenderer`.

`ShapeBuilder.observationData()` exposes the per-xpath observation maps
(iteration values, signatures, rows) that analyzers consume via
`AnalysisContext`. Each analyzer is independently unit-testable against
mocked observation data. Strict mode (`InferenceConfig.strictMode=true`)
promotes every `InferenceWarning` to an `InferenceException`.

When changing behaviour, the integration tests in
`GeneratingXMLEventReaderTest` are the ground truth — they pin down
repeat, increment, nesting, and the interaction between them.

## Build and test

```bash
mvn verify   # compile + tests
mvn test     # tests only
```

Java 21, Maven 3.9+. Tests use JUnit 5 + AssertJ; XML assertions are
done with dom4j XPath.

## House rules for changes

- Keep the StAX decorator chain intact — callers depend on it being a
  plain `XMLEventReader`. New behaviour should fit as another decorator
  rather than as a hook inside an existing one.
- Don't rename public types in `org.brylex.xmlgen` without a reason; the
  artifact is published.
- New template directives should live in the `urn:xml:gen` namespace and
  be stripped from output (mirror `GeneratorStrippingStartEvent`).
- When you add a behaviour, add a test in `GeneratingXMLEventReaderTest`
  that exercises it in isolation and in combination with `repeat` /
  `increment`.
- Don't reintroduce JUnit 4 or FEST-assert. Tests are JUnit Jupiter +
  AssertJ.

## Known rough edges

- A few helper types are named with leading underscores (`_XMLEvent`,
  `_Attribute`). Leave the names alone unless you also update every
  reference.
- The `0.2-SNAPSHOT` version has never been released; treat the module
  as pre-1.0.

## `XMLEventReader` contract

All decorators implement the full `XMLEventReader` contract:

- `nextEvent()` / `peek()` / `hasNext()` — primary stream API.
- `next()` — returns `nextEvent()`, wrapping `XMLStreamException` as
  `IllegalStateException` (`Iterator` doesn't allow checked exceptions).
- `nextTag()` / `getElementText()` — implemented via
  `XMLEventReaders` helpers in terms of each decorator's own
  `nextEvent()`, so template directives (e.g. `gen:increment`) are
  applied to the returned text.
- `close()` / `getProperty()` — delegate down to the source reader so
  resources are released and StAX properties are visible.
- `remove()` — throws `UnsupportedOperationException`, per the
  `Iterator` contract for `XMLEventReader`.
