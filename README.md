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
| `gen:repeat="min..max"` | Repeat the element a random number of times in `[min, max]` per expansion. Uses the same seeded `Random` as the other random-* directives. |
| `gen:increment="N"` | Treat the element's text content as an integer and add `N` to it. Inside a `repeat`, the value accumulates per iteration. |
| `gen:pick="pool/column"` | Replace the element's text with a value from a named pool. Multiple picks against the same pool inside one `gen:repeat` iteration share a row (coherent records). |
| `gen:random-int="min..max"` | Replace the element's text with a random integer in `[min, max]` (inclusive). |
| `gen:random-amount="min..max"` | Replace the element's text with a random decimal in `[min, max]`. Output keeps the maximum scale of `min` and `max` (e.g. `0.00..1000.00` always yields two decimals). |
| `gen:random-date="YYYY-MM-DD..YYYY-MM-DD"` | Replace the element's text with a random ISO date in the closed range. |
| `gen:random-uuid="true"` | Replace the element's text with a Type-4 UUID. Uses the seeded `Random` for reproducibility. |

Plus two **elements** in the same namespace:

| Element | Effect |
| --- | --- |
| `<gen:choose>` containing `<gen:when weight="N">...</gen:when>` children | Emit the contents of exactly one `<gen:when>` branch, picked weighted-randomly. Re-picked on every `gen:repeat` iteration. `weight` defaults to 1 if omitted. |

Attributes and elements in the generator namespace are stripped from
the output.

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

Nested `gen:repeat`s share pin context correctly: an outer pin
(e.g. a customer chosen for an order) survives across inner iterations
(its lines), while inner pins (e.g. a product chosen per line) refresh
per inner iteration. The pin stack is consulted top-down — innermost
scope first, falling back to outer scopes.

## Branching (`gen:choose` / `gen:when`)

Use `<gen:choose>` to emit exactly one of several alternative subtrees.
Each `<gen:when>` child defines a branch; weights bias the random pick.
Inside a `gen:repeat`, each iteration makes its own independent choice.

```xml
<entry xmlns:gen="urn:xml:gen" gen:repeat="100">
    <gen:choose>
        <gen:when weight="60">
            <kind>incoming</kind>
            <party gen:pick="customers/name">_</party>
        </gen:when>
        <gen:when weight="40">
            <kind>outgoing</kind>
            <party gen:pick="suppliers/name">_</party>
        </gen:when>
    </gen:choose>
</entry>
```

Pass a seeded `Random` for reproducible runs:

```java
new GeneratingXMLEventReader(template, pools, new Random(42L));
```

`gen:repeat` works inside `gen:when` branches; each chosen branch
runs through the same directive pipeline as top-level content, so
nesting `gen:repeat` (or any other directive) inside a branch is fully
supported.

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

## Template Inference

Don't have a template? Point `TemplateInferrer` at 5–50 real sample documents
and it emits an annotated template plus `Pools`:

```java
List<XMLEventReader> samples = ...; // your real XML samples

InferredTemplate inferred = TemplateInferrer.infer(samples);
XMLEventReader reader = inferred.expand(new Random(42));
// inferred.templateXml(), inferred.pools(), inferred.warnings() are all available
```

Heuristics applied to the merged shape across samples:
- Repeating siblings become `gen:repeat="min..max"` (or `gen:repeat="N"` when fixed)
- Numeric / decimal / ISO-date fields become `gen:random-*`
- UUID-format fields become `gen:random-uuid="true"`
- Low-cardinality coherent leaf groups (≥ 5 distinct bijective rows) become a shared `gen:pick` with an auto-generated pool
- Alternating sibling sub-trees become `gen:choose` with weighted branches
- Monotonically increasing integer sequences inside `gen:repeat` become `gen:increment="step"`

The inferrer is fully deterministic: same samples → byte-identical template,
regardless of input ordering. Tune via `InferenceConfig`:

```java
InferenceConfig cfg = new InferenceConfig(/* ... */, /* strictMode */ true);
TemplateInferrer.infer(samples, cfg);
```

`strictMode=true` promotes every silent-degradation warning to
`InferenceException`. For anonymization, transform the returned `Pools`
before expansion — see `AnonymizationExampleTest` for a canonical pattern.

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
    GeneratingXMLEventReader.java    # public entry point for expansion
    StackXMLEventReader.java         # stack-backed lookahead/replay
    RecordingXMLEventReader.java     # records subtree for repeat
    DelegatingXMLEventReader.java    # routes between recorders
    TextProcessingXMLEventReader.java# handles increment
    GeneratorStrippingStartEvent.java# strips gen:* attrs from output
    StackEvent.java, _XMLEvent.java, _Attribute.java
    OverriddenCharactersXMLEvent.java
src/main/java/org/brylex/xmlgen/infer/
    TemplateInferrer.java            # public entry point for inference
    InferredTemplate.java            # record(xml, pools, warnings)
    InferenceConfig.java, InferenceException.java, InferenceWarning.java
    ShapeBuilder.java                # XML samples → ShapeNode tree
    ShapeNode.java, ContentItem.java, Directive.java, Range.java, Signature.java
    AnalyzerPipeline.java, AnalysisContext.java, Analyzer.java
    analyzers/RepeatAnalyzer.java        # gen:repeat from sibling cardinality
    analyzers/IncrementAnalyzer.java     # gen:increment from monotonic seqs
    analyzers/RandomRangeAnalyzer.java   # gen:random-{int,amount,date,uuid}
    analyzers/ChooseAnalyzer.java        # gen:choose from signature groups
    analyzers/PickCoherenceAnalyzer.java # gen:pick from bijective leaves
    TemplateRenderer.java            # ShapeNode tree → annotated XML + Pools
```

For a tour aimed at AI assistants, see [CLAUDE.md](CLAUDE.md).

## License

Apache License 2.0.
