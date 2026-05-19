# XmlGen — Testdata Generator

A small Java library for generating XML test data from annotated XML
templates. It works as a decorator around `javax.xml.stream.XMLEventReader`
(StAX), so you can plug it into any tool that already consumes a StAX event
stream — dom4j, JAXB, your own parser, etc.

You write a template that looks like normal XML, with a few extra
attributes in a generator namespace. The reader emits the expanded
document on the fly.

## Features

The generator namespace is `urn:xml:gen` (conventional prefix: `gen`).

| Attribute | Effect |
| --- | --- |
| `gen:repeat="N"` | Repeat the element (and its subtree) `N` times. Nesting multiplies. |
| `gen:increment="N"` | Treat the element's text content as an integer and add `N` to it. Inside a `repeat`, the value accumulates per iteration. |
| `gen:pick="pool/column"` | Replace the element's text with a value from a named pool. Multiple picks against the same pool inside one `gen:repeat` iteration share a row (coherent records). |

Attributes in the generator namespace are stripped from the output.

## Pools (`gen:pick`)

`gen:pick` reads values from named pools you supply when constructing
the reader. Each pool is an ordered list of records (rows), and each row
maps column names to values. Picks advance a per-pool cursor and cycle
when the pool runs out.

Inside one iteration of `gen:repeat`, every pick against a given pool
sees the **same row**, so related fields stay coherent:

```xml
<entry xmlns:gen="urn:xml:gen" gen:repeat="100">
    <party>
        <name gen:pick="customers/name">_</name>
        <iban gen:pick="customers/iban">_</iban>  <!-- same row as name -->
    </party>
</entry>
```

Wire pools into the reader:

```java
Pools pools = Pools.builder()
        .csv("customers", Path.of("customers.csv"))
        .csv("suppliers", Path.of("suppliers.csv"))
        .build();

XMLEventReader reader = new GeneratingXMLEventReader(template, pools);
```

CSVs use the first row as the header and a comma separator. For
test-time data you can also pass rows inline:

```java
Pools.builder()
        .inline("customers", List.of(
                Map.of("name", "Acme AS", "iban", "NO11"),
                Map.of("name", "Beta AS", "iban", "NO22")))
        .build();
```

Nesting `gen:repeat` with picks across multiple levels is not yet
supported — the row pinning is single-frame, so an inner repeat will
clear the outer scope's pins. Keep picks within a single repeat level
for now.

## Example

Template:

```xml
<order xmlns:gen="urn:xml:gen">
    <line gen:repeat="3">
        <seq gen:increment="1">0</seq>
        <sku>ABC-123</sku>
    </line>
</order>
```

Output:

```xml
<order>
    <line><seq>1</seq><sku>ABC-123</sku></line>
    <line><seq>2</seq><sku>ABC-123</sku></line>
    <line><seq>3</seq><sku>ABC-123</sku></line>
</order>
```

## Usage

Wrap any `XMLEventReader` with `GeneratingXMLEventReader`:

```java
XMLEventReader template = XMLInputFactory.newFactory()
        .createXMLEventReader(new StringReader(xml));
XMLEventReader reader = new GeneratingXMLEventReader(template);

// hand `reader` to whoever consumes StAX events
Document document = new STAXEventReader().readDocument(reader);
```

See `src/test/java/org/brylex/xmlgen/GeneratingXMLEventReaderTest.java`
for runnable examples covering `repeat`, `increment`, nesting, and
combinations.

## Build

Requires JDK 21 and Maven 3.9+.

```bash
mvn verify          # compile + run tests
mvn test            # tests only
mvn -q package      # build the jar
```

The artifact is published as `org.brylex:xmlgen` (currently
`0.2-SNAPSHOT`). To consume from Maven once a release ships:

```xml
<dependency>
    <groupId>org.brylex</groupId>
    <artifactId>xmlgen</artifactId>
    <version>...</version>
</dependency>
```

For how to cut a release, see [RELEASING.md](RELEASING.md).

## Project layout

```
src/main/java/org/brylex/xmlgen/
    GeneratingXMLEventReader.java    # public entry point
    StackXMLEventReader.java         # stack-backed lookahead/replay
    RecordingXMLEventReader.java     # records subtree for repeat
    DelegatingXMLEventReader.java    # routes between recorders
    TextProcessingXMLEventReader.java# handles increment
    GeneratorStrippingStartEvent.java# strips gen:* attrs from output
    StackEvent.java, _XMLEvent.java, _Attribute.java
    OverriddenCharactersXMLEvent.java
src/test/java/org/brylex/xmlgen/
    GeneratingXMLEventReaderTest.java
```

For a tour aimed at AI assistants, see [CLAUDE.md](CLAUDE.md).

## License

Apache License 2.0.
