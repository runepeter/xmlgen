# Notes for AI assistants

This file is a map of the repository for AI coding agents (Claude Code,
Copilot, Cursor, etc.). Humans should read [README.md](README.md) first.

## What this project is

A Java library that decorates `javax.xml.stream.XMLEventReader` so that
an annotated XML template expands into generated test data on the fly.
The public entry point is `org.brylex.xmlgen.GeneratingXMLEventReader`.

Two template directives are supported, both in namespace `urn:xml:gen`:

- `gen:repeat="N"` — repeat the element subtree N times
- `gen:increment="N"` — add N to the integer text content (per repeat
  iteration when nested inside `gen:repeat`)

## Architecture

The reader is a chain of decorators, wired in
`GeneratingXMLEventReader`'s constructor:

```
caller
  └── GeneratingXMLEventReader
        └── DelegatingXMLEventReader        // dispatches to recorders
              └── TextProcessingXMLEventReader  // applies gen:increment
                    └── StackXMLEventReader     // lookahead/replay stack
                          └── source XMLEventReader (template)
```

`RecordingXMLEventReader` captures the events inside a `gen:repeat`
subtree and replays them N times onto the `StackXMLEventReader`'s stack
on completion. `GeneratorStrippingStartEvent` removes `gen:*` attributes
from `StartElement` events before they reach the caller.

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

- `StackXMLEventReader.getElementText()` returns the literal string
  `"JALLA"` — it isn't wired to anything in the test suite, but don't
  rely on it. Fix it properly if a caller starts depending on it.
- A few helper types are named with leading underscores (`_XMLEvent`,
  `_Attribute`). Leave the names alone unless you also update every
  reference.
- The `0.2-SNAPSHOT` version has never been released; treat the module
  as pre-1.0.
