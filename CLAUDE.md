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

## Architecture

The reader is a chain of decorators, wired in
`GeneratingXMLEventReader`'s constructor:

```
caller
  └── GeneratingXMLEventReader
        └── DelegatingXMLEventReader          // dispatches to recorders
              └── TextProcessingXMLEventReader    // applies gen:increment
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

`gen:choose` is handled inline in `GeneratingXMLEventReader.nextEvent()`:
when the materialize step sees `<gen:choose>`, `handleChoose()` pulls
events through `delegate.nextEvent()` (so any active gen:repeat recorder
still records the full structure), groups them into branches by
`<gen:when>` boundaries, picks one weighted-randomly, and queues the
chosen events in a `pending` deque. The next `nextEvent()` / `peek()`
call drains `pending` before pulling more from `delegate`. Pending
events bypass the recorder, so each `gen:repeat` iteration re-evaluates
the choose against the recorded structure.

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
