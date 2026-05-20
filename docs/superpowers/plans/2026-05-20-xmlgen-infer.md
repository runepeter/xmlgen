# xmlgen infer — Implementasjons-plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lever auto-template-inferens for xmlgen — `TemplateInferrer.infer(samples)` produserer annotert XML-template + `Pools` fra 5–50 ekte XML-eksempler, samt tre prerequisite kjerne-utvidelser (`gen:repeat="min..max"`, `gen:random-uuid`, og fjerne `gen:repeat`-i-`gen:choose`-begrensningen).

**Architecture:** Toelt design — kjerne-utvidelser først (4 tasks), så inferrer-pipelinen som dekoratør-fritt verktøy i ny subpakke `org.brylex.xmlgen.infer`. Shape-modell bygges fra StAX-pull, dekoratorer (Analyzers) annotater noder med direktiv-beslutninger, TemplateRenderer emitterer annotert XML + Pools.

**Tech Stack:** Java 21, StAX (`javax.xml.stream`), JUnit 5 + AssertJ + dom4j for tester. Ingen nye dependencies.

**Reference:** [Spec](../specs/2026-05-20-xmlgen-infer-design.md). Multi-agent-review innarbeidet — se Review-merknader i spec'en.

---

## Fil-struktur

### Nye filer

**Inferrer-subpakke (`src/main/java/org/brylex/xmlgen/infer/`):**

| Fil | Ansvar |
|---|---|
| `TemplateInferrer.java` | Public entry point — `infer(List<XMLEventReader> samples, [InferenceConfig])` |
| `InferredTemplate.java` | Record: `(templateXml, pools, warnings)` med `expand()`-convenience |
| `InferenceConfig.java` | Record med 10 thresholds + sensible defaults |
| `InferenceException.java` | RuntimeException for fail-loud-situasjoner |
| `InferenceWarning.java` | Sealed interface for diagnostikk |
| `ShapeNode.java` | Tre-node med valueSamples, attributes, cardinality, directive |
| `ContentItem.java` | Sealed interface: ChildSlot / Text / Cdata / PI / Comment |
| `Directive.java` | Sealed interface: Repeat / Increment / Random / Pick / Choose |
| `Range.java` | Sealed interface: IntRange / AmountRange / DateRange / Uuid |
| `ShapeBuilder.java` | StAX → ShapeNode-tre (multi-sample merge) |
| `Analyzer.java` | Interface `void analyze(ShapeNode root, AnalysisContext ctx)` |
| `AnalyzerPipeline.java` | Sekvensiell utførelse av 5 analyzere |
| `TemplateRenderer.java` | Dekorert ShapeNode → XML-streng + Pools |
| `analyzers/RepeatAnalyzer.java` | Setter Repeat(min, max) på noder med varying cardinality |
| `analyzers/IncrementAnalyzer.java` | Detekterer monotont økende heltall innen repeat |
| `analyzers/RandomRangeAnalyzer.java` | Numerisk/desimal/dato/UUID-deteksjon |
| `analyzers/ChooseAnalyzer.java` | Signatur-gruppering, subset-kjede / distinkte / støy |
| `analyzers/PickCoherenceAnalyzer.java` | Bijektiv lav-kardinalitets-gruppering + pool-naming |

**Test-filer (`src/test/java/org/brylex/xmlgen/`):**

| Fil | Ansvar |
|---|---|
| `RepeatRangeTest.java` | Core ext: `gen:repeat="min..max"` parsing + ekspansjon |
| `RandomUuidTest.java` | Core ext: `gen:random-uuid="true"` med seedet Random |
| `RepeatInsideChooseTest.java` | Core ext: gen:repeat inne i gen:when virker |
| `infer/analyzers/RepeatAnalyzerTest.java` etc | Per-analyzer enhetstester (5 stk) |
| `infer/ShapeBuilderTest.java` | XML-strenger → ShapeNode-tre |
| `infer/TemplateRendererTest.java` | Dekorert tre → XML + Pools |
| `infer/TemplateInferrerTest.java` | End-to-end Tier A + Tier B acceptance |
| `infer/DeterminismTest.java` | shuffled-input gir identisk output |
| `infer/AnonymizationExampleTest.java` | Eksempel + literal-free property |

**Test-fixtures (`src/test/resources/infer/`):**

| Path | Innhold |
|---|---|
| `tier-a/typical-invoice/sample-*.xml` | 5–10 håndlagde faktura-samples |
| `tier-a/homogeneous/sample-*.xml` | 10 samples med ≤ 5 distinkte verdier per felt |
| `tier-a/nested-repeat/sample-*.xml` | Outer/inner repeat med koherens |
| `tier-a/optional-elements/sample-*.xml` | Subset-kjede mønster |
| `tier-b/typical-invoice/{samples/,expected.xml}` | Golden-fil-fixture |
| `tier-b/nested-coherence/{samples/,expected.xml}` | Nested + cross-level pick |
| `tier-b/choose-distinct/{samples/,expected.xml}` | Mutually-distinct signatures |
| `tier-b/uuid-detection/{samples/,expected.xml}` | UUID-format + WARNING-kommentar |

### Modifiserte filer

| Fil | Endring |
|---|---|
| `_XMLEvent.java` | Støtt `gen:repeat="min..max"`-syntax; ta Random i konstruktør |
| `StackEvent.java` | Thread Random gjennom til _XMLEvent |
| `RecordingXMLEventReader.java` | Pass Random ned ved nye StackEvent-er |
| `GeneratingXMLEventReader.java` | Pass `pending`-events gjennom recorder; nytt StackEvent-API |
| `RandomXMLEventReader.java` | Legg til `gen:random-uuid="true"`-håndtering |
| `README.md` | Ny seksjon: "Template Inference" |
| `CLAUDE.md` | Pek på `infer`-subpakke i arkitektur-seksjonen |

---

## Phase 1 — Kjerne-utvidelser

### Task 1: `gen:repeat="min..max"`-syntax i `_XMLEvent`

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/_XMLEvent.java`
- Modify: `src/main/java/org/brylex/xmlgen/StackEvent.java`
- Modify: `src/main/java/org/brylex/xmlgen/RecordingXMLEventReader.java`
- Modify: `src/main/java/org/brylex/xmlgen/GeneratingXMLEventReader.java`
- Test: `src/test/java/org/brylex/xmlgen/RepeatRangeTest.java`

- [ ] **Step 1: Write the failing test**

```java
// RepeatRangeTest.java
package org.brylex.xmlgen;

import org.dom4j.Document;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatRangeTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void repeatRangeDrawsCountWithinBounds() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="2..5"><sku>X</sku></line>
                </order>
                """;
        for (int seed = 0; seed < 20; seed++) {
            Document doc = expand(template, seed);
            int lineCount = doc.selectNodes("/order/line").size();
            assertThat(lineCount)
                    .as("seed=%d", seed)
                    .isBetween(2, 5);
        }
    }

    @Test
    void seedDeterminesRangeDraw() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="1..10"><sku>X</sku></line>
                </order>
                """;
        int firstRun = expand(template, 42).selectNodes("/order/line").size();
        int secondRun = expand(template, 42).selectNodes("/order/line").size();
        assertThat(firstRun).isEqualTo(secondRun);
    }

    @Test
    void fixedRepeatStillWorks() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="3"><sku>X</sku></line>
                </order>
                """;
        Document doc = expand(template, 0);
        assertThat(doc.selectNodes("/order/line")).hasSize(3);
    }

    private Document expand(String template, long seed) throws Exception {
        XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(template));
        XMLEventReader reader = new GeneratingXMLEventReader(source, Pools.empty(), new Random(seed));
        return new STAXEventReader().readDocument(reader);
    }
}
```

- [ ] **Step 2: Run test, verify failure**

Run: `mvn -Dtest=RepeatRangeTest test`
Expected: FAIL with `NumberFormatException: For input string: "2..5"` from `_XMLEvent.java:37`

- [ ] **Step 3: Implement range parsing in `_XMLEvent`**

Modify `_XMLEvent.java`:

```java
// Add field
private final Random random;

// Change constructor signature
public _XMLEvent(final StartElement delegate, final Random random) {
    this.random = random;
    for (Iterator<Attribute> it = delegate.getAttributes(); it.hasNext(); ) {
        Attribute attribute = it.next();
        QName qName = attribute.getName();

        if ("urn:xml:gen".equals(qName.getNamespaceURI())) {

            if (REPEAT.equals(qName)) {
                int resolved = resolveRepeat(attribute.getValue());
                if (resolved > 1) {
                    this.template.set(true);
                }
                // Replace original attribute with resolved fixed-integer value
                // so decrementRepeat() can use the existing integer pathway.
                attributes.put(qName, new _Attribute(attribute, Integer.toString(resolved)));
            } else {
                attributes.put(qName, attribute);
            }

            if (INCREMENT.equals(qName)) {
                this.increment = Integer.parseInt(attribute.getValue());
            }

        } else {
            attributes.put(qName, attribute);
        }
    }

    StartElement raw = delegate;
    while (raw instanceof _XMLEvent inner) {
        raw = inner.delegate;
    }
    this.delegate = raw;
}

private int resolveRepeat(String value) {
    int sep = value.indexOf("..");
    if (sep < 0) {
        return Integer.parseInt(value);
    }
    int min = Integer.parseInt(value.substring(0, sep).trim());
    int max = Integer.parseInt(value.substring(sep + 2).trim());
    if (min > max) {
        throw new IllegalArgumentException(
                "gen:repeat min must be <= max, got '" + value + "'");
    }
    if (min == max) {
        return min;
    }
    return min + random.nextInt(max - min + 1);
}
```

- [ ] **Step 4: Thread Random through `StackEvent` and `RecordingXMLEventReader`**

Modify `StackEvent.java`:

```java
class StackEvent {
    private final XMLEvent event;
    private int increment;

    StackEvent(final XMLEvent event, final Random random) {
        if (event.isStartElement()) {
            this.event = new _XMLEvent(event.asStartElement(), random);
        } else {
            this.event = event;
        }
    }
    // ... rest unchanged
}
```

Modify `RecordingXMLEventReader.java`:

```java
class RecordingXMLEventReader implements XMLEventReader {

    private final XMLEventReader parent;
    private final Random random;
    private final Stack<StackEvent> record = new Stack<>();
    private final Set<Location> locations = new HashSet<>();
    private int count = 1;

    RecordingXMLEventReader(final XMLEventReader parent, final StackEvent stackEvent, final Random random) {
        this.parent = parent;
        this.random = random;
        record.push(stackEvent);
        locations.add(stackEvent.getEvent().getLocation());
    }

    public XMLEvent nextEvent() throws XMLStreamException {
        XMLEvent event = parent.nextEvent();
        if (!locations.contains(event.getLocation())) {
            locations.add(event.getLocation());

            if (event.isStartElement()) count++;
            if (event.isEndElement()) count--;

            record.push(new StackEvent(event, random));
        }
        return event;
    }
    // ... rest unchanged
}
```

Modify `GeneratingXMLEventReader.java`:

```java
public XMLEvent nextEvent() throws XMLStreamException {
    materialize();
    XMLEvent raw = head;
    head = null;

    StackEvent event = new StackEvent(raw, random);

    if (event.isTemplate()) {
        RecordingXMLEventReader recordingReader = new RecordingXMLEventReader(delegate.current(), event, random);
        delegate.newRecorder(recordingReader);
        return returnableEvent(event);
    }
    // ... rest unchanged
}
```

- [ ] **Step 5: Run test, verify pass**

Run: `mvn -Dtest=RepeatRangeTest test`
Expected: PASS (all 3 tests)

- [ ] **Step 6: Run full suite to verify no regression**

Run: `mvn test`
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/_XMLEvent.java \
        src/main/java/org/brylex/xmlgen/StackEvent.java \
        src/main/java/org/brylex/xmlgen/RecordingXMLEventReader.java \
        src/main/java/org/brylex/xmlgen/GeneratingXMLEventReader.java \
        src/test/java/org/brylex/xmlgen/RepeatRangeTest.java
git commit -m "Support gen:repeat=\"min..max\" range syntax"
```

---

### Task 2: `gen:random-uuid="true"`-direktiv

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/RandomXMLEventReader.java`
- Test: `src/test/java/org/brylex/xmlgen/RandomUuidTest.java`

- [ ] **Step 1: Write the failing test**

```java
// RandomUuidTest.java
package org.brylex.xmlgen;

import org.dom4j.Document;
import org.dom4j.Node;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RandomUuidTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();
    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Test
    void uuidDirectiveEmitsParseableUuid() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <id gen:random-uuid="true">_</id>
                </order>
                """;
        Document doc = expand(template, 1);
        String id = doc.selectSingleNode("/order/id").getText();
        assertThat(id).matches(UUID_REGEX);
        UUID.fromString(id); // throws if invalid
    }

    @Test
    void uuidIsDeterministicWithSameSeed() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <id gen:random-uuid="true">_</id>
                </order>
                """;
        String first = expand(template, 42).selectSingleNode("/order/id").getText();
        String second = expand(template, 42).selectSingleNode("/order/id").getText();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void uuidVariesAcrossRepeats() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="20"><id gen:random-uuid="true">_</id></line>
                </order>
                """;
        Document doc = expand(template, 7);
        @SuppressWarnings("unchecked")
        List<Node> ids = doc.selectNodes("/order/line/id");
        Set<String> distinct = new HashSet<>();
        for (Node n : ids) distinct.add(n.getText());
        assertThat(distinct).hasSize(20);
    }

    private Document expand(String template, long seed) throws Exception {
        XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(template));
        XMLEventReader reader = new GeneratingXMLEventReader(source, Pools.empty(), new Random(seed));
        return new STAXEventReader().readDocument(reader);
    }
}
```

- [ ] **Step 2: Run test, verify failure**

Run: `mvn -Dtest=RandomUuidTest test`
Expected: FAIL — UUID-attribute ignored, body remains `"_"`

- [ ] **Step 3: Add UUID generator to `RandomXMLEventReader`**

Modify `RandomXMLEventReader.java`:

```java
// Add to constants
static final QName RANDOM_UUID = new QName("urn:xml:gen", "random-uuid");

// In parseGenerator(), add new branch BEFORE the existing ones
private static Generator parseGenerator(StartElement se) {
    Attribute attr = se.getAttributeByName(RANDOM_UUID);
    if (attr != null) {
        if (!"true".equals(attr.getValue())) {
            throw new IllegalArgumentException(
                    "gen:random-uuid value must be 'true', got '" + attr.getValue() + "'");
        }
        return rng -> new UUID(rng.nextLong(), rng.nextLong()).toString();
    }
    attr = se.getAttributeByName(RANDOM_INT);
    // ... rest unchanged
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `mvn -Dtest=RandomUuidTest test`
Expected: PASS

- [ ] **Step 5: Verify UUID Variant/Version bits**

Note: `new UUID(rng.nextLong(), rng.nextLong())` does NOT set version/variant bits like `UUID.randomUUID()` does. Verify the test regex still matches by inspecting actual output:

Run: `mvn -Dtest=RandomUuidTest#uuidDirectiveEmitsParseableUuid -Dsurefire.useFile=false test`

If the regex fails: adjust UUID generation to set Type 4 bits explicitly:

```java
return rng -> {
    long msb = rng.nextLong();
    long lsb = rng.nextLong();
    msb &= ~(0xFL << 12); msb |= 0x4L << 12;       // version 4
    lsb &= ~(0xC000000000000000L); lsb |= 0x8000000000000000L; // variant 10
    return new UUID(msb, lsb).toString();
};
```

- [ ] **Step 6: Run full suite**

Run: `mvn test`
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/RandomXMLEventReader.java \
        src/test/java/org/brylex/xmlgen/RandomUuidTest.java
git commit -m "Add gen:random-uuid directive for seedable UUID generation"
```

---

### Task 3: Lift `gen:repeat`-inside-`gen:choose` limitation

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/GeneratingXMLEventReader.java`
- Modify: `src/test/java/org/brylex/xmlgen/ChooseTest.java` (oppdater begrensningstest)
- Test: `src/test/java/org/brylex/xmlgen/RepeatInsideChooseTest.java`

- [ ] **Step 1: Write the failing test**

```java
// RepeatInsideChooseTest.java
package org.brylex.xmlgen;

import org.dom4j.Document;
import org.dom4j.Node;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatInsideChooseTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void repeatInsideChosenBranchExpands() throws Exception {
        String template = """
                <entry xmlns:gen="urn:xml:gen">
                    <gen:choose>
                        <gen:when weight="1">
                            <line gen:repeat="3"><sku>A</sku></line>
                        </gen:when>
                        <gen:when weight="0">
                            <line><sku>B</sku></line>
                        </gen:when>
                    </gen:choose>
                </entry>
                """;
        Document doc = expand(template, 42);
        @SuppressWarnings("unchecked")
        List<Node> skus = doc.selectNodes("//sku");
        assertThat(skus).hasSize(3);
        for (Node n : skus) {
            assertThat(n.getText()).isEqualTo("A");
        }
    }

    @Test
    void outerRepeatWithInnerChooseContainingRepeat() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <section gen:repeat="2">
                        <gen:choose>
                            <gen:when weight="1">
                                <item gen:repeat="2"><x>A</x></item>
                            </gen:when>
                            <gen:when weight="0">
                                <item><x>B</x></item>
                            </gen:when>
                        </gen:choose>
                    </section>
                </order>
                """;
        Document doc = expand(template, 1);
        @SuppressWarnings("unchecked")
        List<Node> items = doc.selectNodes("//item");
        // 2 sections × 2 items each = 4 items
        assertThat(items).hasSize(4);
    }

    private Document expand(String template, long seed) throws Exception {
        XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(template));
        XMLEventReader reader = new GeneratingXMLEventReader(source, Pools.empty(), new Random(seed));
        return new STAXEventReader().readDocument(reader);
    }
}
```

- [ ] **Step 2: Run test, verify failure**

Run: `mvn -Dtest=RepeatInsideChooseTest test`
Expected: FAIL — repeat inside chosen branch produces only 1 element

- [ ] **Step 3: Route `pending` events through recorder**

Modify `GeneratingXMLEventReader.materialize()` so events drained from `pending` ALSO go through the recorder if one is active. Easiest: when chosen events are added to pending, also push them onto the recorder's internal state via the existing `delegate` chain.

Reimplement strategy: instead of putting raw events in `pending`, replay them through the `delegate.nextEvent()` pathway via a temporary event-injecting wrapper. Simplest implementation:

```java
private void materialize() throws XMLStreamException {
    while (head == null) {
        if (!pending.isEmpty()) {
            XMLEvent event = pending.poll();
            // If a recorder is active, route this event through it so
            // gen:repeat inside the chosen branch is captured for replay.
            if (delegate.isRecording()) {
                head = delegate.feedEvent(event);
            } else {
                head = event;
            }
        } else {
            XMLEvent raw = delegate.nextEvent();
            if (isChooseStart(raw)) {
                handleChoose();
                continue;
            }
            head = raw;
        }
    }
}
```

This requires adding `feedEvent(XMLEvent)` to `DelegatingXMLEventReader`. Add to `DelegatingXMLEventReader.java`:

```java
/**
 * Replay an event through the active recorder so directives embedded in
 * the chosen branch (e.g. gen:repeat inside gen:when) are captured.
 * Returns the event unchanged for emission to the caller.
 */
public XMLEvent feedEvent(XMLEvent event) throws XMLStreamException {
    if (isRecording()) {
        // The recorder's nextEvent reads from `parent`; we can't push events
        // back into parent. Instead, manually push into the recorder's record
        // and locations. Expose a setter on RecordingXMLEventReader.
        peekRecorder().captureExternal(event);
    }
    return event;
}
```

And add to `RecordingXMLEventReader.java`:

```java
/**
 * Capture an event that was emitted externally (e.g. from a gen:choose
 * branch) so it gets replayed by gen:repeat alongside the recorded
 * subtree.
 */
public void captureExternal(XMLEvent event) {
    if (event.isStartElement()) count++;
    if (event.isEndElement()) count--;
    record.push(new StackEvent(event, random));
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `mvn -Dtest=RepeatInsideChooseTest test`
Expected: PASS

- [ ] **Step 5: Update existing `ChooseTest` that pinned the limitation**

Search for the existing test that documented "gen:repeat inside gen:when is not supported" and either:
- Delete it (the limitation no longer exists)
- Convert it to a positive test demonstrating the new capability

Run: `grep -rn "not supported" src/test/java/org/brylex/xmlgen/ChooseTest.java`

If a comment or test exists, update it. Typical change: rename a test method like `repeatInsideWhenIsNotSupported` to `repeatInsideWhenExpands` and flip the assertion.

- [ ] **Step 6: Run full suite**

Run: `mvn test`
Expected: All tests pass (including any updated ChooseTest assertions)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/GeneratingXMLEventReader.java \
        src/main/java/org/brylex/xmlgen/DelegatingXMLEventReader.java \
        src/main/java/org/brylex/xmlgen/RecordingXMLEventReader.java \
        src/test/java/org/brylex/xmlgen/ChooseTest.java \
        src/test/java/org/brylex/xmlgen/RepeatInsideChooseTest.java
git commit -m "Support gen:repeat inside gen:choose branches"
```

---

### Task 4: README-oppdatering for kjerne-utvidelser

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the features table**

Add two rows to the directive table near the top:

```markdown
| `gen:repeat="min..max"` | Repeat the element a random number of times in `[min, max]` per expansion. Uses the same seeded `Random` as other random-* directives. |
| `gen:random-uuid="true"` | Replace the element's text with a Type-4 UUID. Uses the seeded `Random` for reproducibility. |
```

And remove the documented limitation paragraph:

```markdown
`gen:repeat` *inside* a `gen:when` branch is not supported in this
release — ...
```

Replace with:

```markdown
`gen:repeat` works inside `gen:when` branches; each chosen branch
runs through the same directive pipeline as top-level content.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "Document new directives and lifted gen:repeat-inside-choose limitation"
```

---

## Phase 2 — Inferrer infrastructure types

### Task 5: `InferenceException` + `InferenceConfig` + `InferenceWarning`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/InferenceException.java`
- Create: `src/main/java/org/brylex/xmlgen/infer/InferenceConfig.java`
- Create: `src/main/java/org/brylex/xmlgen/infer/InferenceWarning.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/InferenceConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
// InferenceConfigTest.java
package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceConfigTest {

    @Test
    void defaultsHaveSensibleValues() {
        InferenceConfig cfg = InferenceConfig.defaults();
        assertThat(cfg.lowCardinalityRatio()).isEqualTo(0.20);
        assertThat(cfg.lowCardinalityMaxDistinct()).isEqualTo(50);
        assertThat(cfg.randomCardinalityThreshold()).isEqualTo(0.50);
        assertThat(cfg.minPoolRows()).isEqualTo(5);
        assertThat(cfg.chooseNoiseThreshold()).isEqualTo(0.10);
        assertThat(cfg.chooseMaxBranches()).isEqualTo(4);
        assertThat(cfg.maxValueSamplesPerNode()).isEqualTo(1000);
        assertThat(cfg.maxSampleDepth()).isEqualTo(100);
        assertThat(cfg.allowVariableAttributes()).isFalse();
        assertThat(cfg.strictMode()).isFalse();
    }
}
```

- [ ] **Step 2: Create `InferenceException`**

```java
// InferenceException.java
package org.brylex.xmlgen.infer;

import java.util.List;

public class InferenceException extends RuntimeException {

    private final List<InferenceWarning> warnings;

    public InferenceException(String message) {
        this(message, null, List.of());
    }

    public InferenceException(String message, Throwable cause) {
        this(message, cause, List.of());
    }

    public InferenceException(String message, List<InferenceWarning> warnings) {
        this(message, null, warnings);
    }

    public InferenceException(String message, Throwable cause, List<InferenceWarning> warnings) {
        super(message, cause);
        this.warnings = List.copyOf(warnings);
    }

    public List<InferenceWarning> warnings() {
        return warnings;
    }
}
```

- [ ] **Step 3: Create `InferenceConfig`**

```java
// InferenceConfig.java
package org.brylex.xmlgen.infer;

public record InferenceConfig(
        double lowCardinalityRatio,
        int lowCardinalityMaxDistinct,
        double randomCardinalityThreshold,
        int minPoolRows,
        double chooseNoiseThreshold,
        int chooseMaxBranches,
        int maxValueSamplesPerNode,
        int maxSampleDepth,
        boolean allowVariableAttributes,
        boolean strictMode
) {
    public static InferenceConfig defaults() {
        return new InferenceConfig(
                0.20,   // lowCardinalityRatio
                50,     // lowCardinalityMaxDistinct
                0.50,   // randomCardinalityThreshold
                5,      // minPoolRows
                0.10,   // chooseNoiseThreshold
                4,      // chooseMaxBranches
                1000,   // maxValueSamplesPerNode
                100,    // maxSampleDepth
                false,  // allowVariableAttributes
                false   // strictMode
        );
    }
}
```

- [ ] **Step 4: Create `InferenceWarning` sealed interface**

```java
// InferenceWarning.java
package org.brylex.xmlgen.infer;

public sealed interface InferenceWarning {
    String xpath();
    String message();

    record DroppedRareSignature(String xpath, String signatureRepr, int count, int totalObservations) implements InferenceWarning {
        public String message() {
            return "dropped rare signature " + signatureRepr + " (" + count + "/" + totalObservations + ")";
        }
    }
    record ChooseBranchOverflow(String xpath, int branchCount, int max) implements InferenceWarning {
        public String message() {
            return "choose at " + xpath + " has " + branchCount + " branches, capped at " + max;
        }
    }
    record AmbiguousOrder(String xpath, String chosenSequence, String alternativeSequence) implements InferenceWarning {
        public String message() {
            return "ambiguous child order at " + xpath + "; chose " + chosenSequence + " over " + alternativeSequence;
        }
    }
    record VariableAttributeFallback(String xpath, String attributeName, int distinctValues) implements InferenceWarning {
        public String message() {
            return "attribute " + attributeName + " at " + xpath + " has " + distinctValues + " distinct values; using first-observed";
        }
    }
    record ValueSamplesTruncated(String xpath, int kept, int total) implements InferenceWarning {
        public String message() {
            return "value samples at " + xpath + " truncated: kept " + kept + " of " + total;
        }
    }
    record UniqueValueCycleRisk(String xpath, int observations) implements InferenceWarning {
        public String message() {
            return "all " + observations + " observed values at " + xpath + " are unique; pool will cycle on expansion";
        }
    }
    record MixedTypeFallback(String xpath, String reason) implements InferenceWarning {
        public String message() {
            return "field at " + xpath + " has mixed types: " + reason;
        }
    }
    record PartialBijectionRejected(String xpath, double bijectionRatio) implements InferenceWarning {
        public String message() {
            return "partial bijection at " + xpath + " (ratio=" + bijectionRatio + "); using individual picks";
        }
    }
}
```

- [ ] **Step 5: Run test, verify pass**

Run: `mvn -Dtest=InferenceConfigTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/InferenceException.java \
        src/main/java/org/brylex/xmlgen/infer/InferenceConfig.java \
        src/main/java/org/brylex/xmlgen/infer/InferenceWarning.java \
        src/test/java/org/brylex/xmlgen/infer/InferenceConfigTest.java
git commit -m "Add InferenceConfig, InferenceWarning, InferenceException types"
```

---

### Task 6: `ContentItem` + `ShapeNode`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/ContentItem.java`
- Create: `src/main/java/org/brylex/xmlgen/infer/ShapeNode.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/ShapeNodeTest.java`

- [ ] **Step 1: Write the failing test**

```java
// ShapeNodeTest.java
package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeNodeTest {

    @Test
    void newNodeIsEmpty() {
        ShapeNode node = new ShapeNode(new QName("order"), "/order");
        assertThat(node.qName().getLocalPart()).isEqualTo("order");
        assertThat(node.xpath()).isEqualTo("/order");
        assertThat(node.orderedContent()).isEmpty();
        assertThat(node.valueSamples().isEmpty()).isTrue();
        assertThat(node.attributes()).isEmpty();
        assertThat(node.hasMixedContent()).isFalse();
        assertThat(node.directive()).isEmpty();
    }

    @Test
    void setDirectiveStoresValue() {
        ShapeNode node = new ShapeNode(new QName("line"), "/order/line");
        node.setDirective(new Directive.Repeat(2, 5));
        assertThat(node.directive()).contains(new Directive.Repeat(2, 5));
    }
}
```

- [ ] **Step 2: Create `ContentItem` sealed interface**

```java
// ContentItem.java
package org.brylex.xmlgen.infer;

public sealed interface ContentItem {
    record ChildSlot(ShapeNode node) implements ContentItem {}
    record Text(String value) implements ContentItem {}
    record Cdata(String value) implements ContentItem {}
    record ProcessingInstruction(String target, String data) implements ContentItem {}
    record Comment(String value) implements ContentItem {}
}
```

- [ ] **Step 3: Create `ShapeNode`**

```java
// ShapeNode.java
package org.brylex.xmlgen.infer;

import javax.xml.namespace.QName;
import java.util.*;

public final class ShapeNode {

    private final QName qName;
    private final String xpath;
    private final List<ContentItem> orderedContent = new ArrayList<>();
    private final ValueSamples valueSamples = new ValueSamples();
    private final IntSummaryStatistics cardinalityPerParent = new IntSummaryStatistics();
    private final Map<QName, ValueSamples> attributes = new LinkedHashMap<>();
    private boolean hasMixedContent;
    private Directive directive;

    public ShapeNode(QName qName, String xpath) {
        this.qName = qName;
        this.xpath = xpath;
    }

    public QName qName() { return qName; }
    public String xpath() { return xpath; }
    public List<ContentItem> orderedContent() { return orderedContent; }
    public ValueSamples valueSamples() { return valueSamples; }
    public IntSummaryStatistics cardinalityPerParent() { return cardinalityPerParent; }
    public Map<QName, ValueSamples> attributes() { return attributes; }

    public boolean hasMixedContent() { return hasMixedContent; }
    public void markMixedContent() { this.hasMixedContent = true; }

    public Optional<Directive> directive() { return Optional.ofNullable(directive); }
    public void setDirective(Directive directive) {
        if (this.directive != null) {
            throw new IllegalStateException(
                    "Directive already set on " + xpath + ": " + this.directive);
        }
        this.directive = directive;
    }

    /** Multiset wrapper used for valueSamples and attribute observations. */
    public static final class ValueSamples {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private int total;
        private int truncatedSince;

        public void add(String value) {
            counts.merge(value, 1, Integer::sum);
            total++;
        }
        public boolean isEmpty() { return total == 0; }
        public int total() { return total; }
        public Map<String, Integer> counts() { return counts; }
        public Set<String> distinct() { return counts.keySet(); }
        public int distinctCount() { return counts.size(); }
        public void recordTruncation(int dropped) { this.truncatedSince += dropped; }
        public int truncatedCount() { return truncatedSince; }
    }
}
```

- [ ] **Step 4: Create `Directive` sealed interface (used by ShapeNode tests)**

```java
// Directive.java
package org.brylex.xmlgen.infer;

import java.util.List;

public sealed interface Directive {
    record Repeat(int min, int max) implements Directive {
        public boolean isFixed() { return min == max; }
    }
    record Increment(int step, int startingValue) implements Directive {}
    record Random(Range range) implements Directive {}
    record Pick(String poolName, String column) implements Directive {}
    record Choose(List<Branch> branches) implements Directive {}

    record Branch(int weight, List<ContentItem> body) {}
}
```

- [ ] **Step 5: Create `Range` sealed interface**

```java
// Range.java
package org.brylex.xmlgen.infer;

import java.time.LocalDate;
import java.math.BigDecimal;

public sealed interface Range {
    record IntRange(long min, long max) implements Range {}
    record AmountRange(BigDecimal min, BigDecimal max, int scale) implements Range {}
    record DateRange(LocalDate min, LocalDate max) implements Range {}
    record Uuid() implements Range {}
}
```

- [ ] **Step 6: Run test, verify pass**

Run: `mvn -Dtest=ShapeNodeTest test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/ContentItem.java \
        src/main/java/org/brylex/xmlgen/infer/ShapeNode.java \
        src/main/java/org/brylex/xmlgen/infer/Directive.java \
        src/main/java/org/brylex/xmlgen/infer/Range.java \
        src/test/java/org/brylex/xmlgen/infer/ShapeNodeTest.java
git commit -m "Add ShapeNode, ContentItem, Directive, Range datatypes"
```

---

### Task 7: `InferredTemplate` record

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/InferredTemplate.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/InferredTemplateTest.java`

- [ ] **Step 1: Write the failing test**

```java
// InferredTemplateTest.java
package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pools;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class InferredTemplateTest {

    private static final String SIMPLE = """
            <order xmlns:gen="urn:xml:gen"><line><sku>A</sku></line></order>
            """;

    @Test
    void recordHoldsValues() {
        InferredTemplate t = new InferredTemplate(SIMPLE, Pools.empty(), List.of());
        assertThat(t.templateXml()).isEqualTo(SIMPLE);
        assertThat(t.pools().isEmpty()).isTrue();
        assertThat(t.warnings()).isEmpty();
    }

    @Test
    void expandReturnsWorkingReader() throws Exception {
        InferredTemplate t = new InferredTemplate(SIMPLE, Pools.empty(), List.of());
        XMLEventReader reader = t.expand(new Random(0));
        int events = 0;
        while (reader.hasNext()) { reader.next(); events++; }
        assertThat(events).isGreaterThan(5);
    }
}
```

- [ ] **Step 2: Create `InferredTemplate`**

```java
// InferredTemplate.java
package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.GeneratingXMLEventReader;
import org.brylex.xmlgen.Pools;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.StringReader;
import java.util.List;
import java.util.Random;

public record InferredTemplate(
        String templateXml,
        Pools pools,
        List<InferenceWarning> warnings
) {
    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    public InferredTemplate {
        warnings = List.copyOf(warnings);
    }

    public XMLEventReader expand(Random random) {
        try {
            XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(templateXml));
            return new GeneratingXMLEventReader(source, pools, random);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("inferred template failed to parse: " + e.getMessage(), e);
        }
    }

    public XMLEventReader expand() {
        return expand(new Random());
    }
}
```

- [ ] **Step 3: Run test, verify pass**

Run: `mvn -Dtest=InferredTemplateTest test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/InferredTemplate.java \
        src/test/java/org/brylex/xmlgen/infer/InferredTemplateTest.java
git commit -m "Add InferredTemplate record with expand convenience"
```

---

### Task 8: `TemplateInferrer` skeleton (stub som returnerer tom template)

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/TemplateInferrer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/TemplateInferrerStubTest.java`

- [ ] **Step 1: Write the failing test**

```java
// TemplateInferrerStubTest.java
package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateInferrerStubTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void emptySamplesFailLoud() {
        assertThatThrownBy(() -> TemplateInferrer.infer(List.of()))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("at least one sample");
    }

    @Test
    void singleSampleReturnsInferredTemplate() throws Exception {
        XMLEventReader sample = FACTORY.createXMLEventReader(
                new StringReader("<order><line><sku>A</sku></line></order>"));
        InferredTemplate t = TemplateInferrer.infer(List.of(sample));
        assertThat(t.templateXml()).contains("order");
        assertThat(t.warnings()).isNotNull();
    }
}
```

- [ ] **Step 2: Create `TemplateInferrer` stub**

```java
// TemplateInferrer.java
package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pools;

import javax.xml.stream.XMLEventReader;
import java.util.List;

public final class TemplateInferrer {

    private TemplateInferrer() {}

    public static InferredTemplate infer(List<XMLEventReader> samples) {
        return infer(samples, InferenceConfig.defaults());
    }

    public static InferredTemplate infer(List<XMLEventReader> samples, InferenceConfig config) {
        if (samples.isEmpty()) {
            throw new InferenceException("at least one sample required");
        }
        // Stub: emit empty template. Tasks 9-20 fill in real pipeline.
        return new InferredTemplate(
                "<root xmlns:gen=\"urn:xml:gen\"/>",
                Pools.empty(),
                List.of()
        );
    }
}
```

- [ ] **Step 3: Run test, verify pass**

Run: `mvn -Dtest=TemplateInferrerStubTest test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/TemplateInferrer.java \
        src/test/java/org/brylex/xmlgen/infer/TemplateInferrerStubTest.java
git commit -m "Add TemplateInferrer stub entry point"
```

---

## Phase 3 — ShapeBuilder

### Task 9: ShapeBuilder for single sample (struktur og value-samples)

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/ShapeBuilder.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/ShapeBuilderTest.java`

- [ ] **Step 1: Write the failing test**

```java
// ShapeBuilderTest.java
package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeBuilderTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void buildsTreeFromSingleSample() throws Exception {
        ShapeNode root = build("""
                <order>
                    <line><sku>X1</sku><qty>5</qty></line>
                    <line><sku>X2</sku><qty>3</qty></line>
                </order>
                """);

        assertThat(root.qName().getLocalPart()).isEqualTo("order");
        assertThat(root.xpath()).isEqualTo("/order");

        ShapeNode line = onlyChildSlot(root).node();
        assertThat(line.qName().getLocalPart()).isEqualTo("line");
        assertThat(line.cardinalityPerParent().getMax()).isEqualTo(2);

        ShapeNode sku = findChild(line, "sku");
        assertThat(sku.valueSamples().counts()).containsKeys("X1", "X2");
    }

    @Test
    void mergesMultipleSamples() throws Exception {
        ShapeNode root = build(
                "<order><line><sku>X1</sku></line></order>",
                "<order><line><sku>X2</sku></line><line><sku>X3</sku></line></order>"
        );
        ShapeNode line = onlyChildSlot(root).node();
        ShapeNode sku = findChild(line, "sku");
        assertThat(sku.valueSamples().counts()).containsKeys("X1", "X2", "X3");
        assertThat(line.cardinalityPerParent().getMin()).isEqualTo(1);
        assertThat(line.cardinalityPerParent().getMax()).isEqualTo(2);
    }

    private ShapeNode build(String... samples) throws Exception {
        ShapeBuilder builder = new ShapeBuilder(InferenceConfig.defaults());
        for (String s : samples) {
            XMLEventReader r = FACTORY.createXMLEventReader(new StringReader(s));
            builder.absorb(r);
        }
        return builder.build();
    }

    private ContentItem.ChildSlot onlyChildSlot(ShapeNode parent) {
        List<ContentItem.ChildSlot> slots = parent.orderedContent().stream()
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> (ContentItem.ChildSlot) ci)
                .toList();
        assertThat(slots).hasSize(1);
        return slots.get(0);
    }

    private ShapeNode findChild(ShapeNode parent, String localName) {
        return parent.orderedContent().stream()
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> ((ContentItem.ChildSlot) ci).node())
                .filter(n -> n.qName().getLocalPart().equals(localName))
                .findFirst().orElseThrow();
    }
}
```

- [ ] **Step 2: Run test, verify failure**

Run: `mvn -Dtest=ShapeBuilderTest test`
Expected: FAIL (`ShapeBuilder` does not exist)

- [ ] **Step 3: Implement `ShapeBuilder.absorb()` for single sample**

```java
// ShapeBuilder.java
package org.brylex.xmlgen.infer;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.*;
import java.util.*;

public class ShapeBuilder {

    private static final String GEN_NS = "urn:xml:gen";
    private final InferenceConfig config;
    private final List<SampleObservation> samples = new ArrayList<>();

    public ShapeBuilder(InferenceConfig config) {
        this.config = config;
    }

    public void absorb(XMLEventReader reader) throws XMLStreamException {
        SampleObservation observation = walk(reader, samples.size() + 1);
        samples.add(observation);
    }

    public ShapeNode build() {
        if (samples.isEmpty()) {
            throw new InferenceException("at least one sample required");
        }
        QName rootName = samples.get(0).rootName;
        for (SampleObservation s : samples) {
            if (!s.rootName.equals(rootName)) {
                throw new InferenceException(
                        "samples disagree on root element: "
                                + rootName.getLocalPart() + " vs " + s.rootName.getLocalPart());
            }
        }
        return mergeShapes();
    }

    private SampleObservation walk(XMLEventReader reader, int sampleIndex) throws XMLStreamException {
        Deque<ShapeNode> stack = new ArrayDeque<>();
        SampleObservation observation = new SampleObservation();
        int depth = 0;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                depth++;
                if (depth > config.maxSampleDepth()) {
                    throw new InferenceException(
                            "sample " + sampleIndex + " exceeds maxSampleDepth=" + config.maxSampleDepth());
                }
                StartElement se = event.asStartElement();
                QName name = se.getName();
                if (GEN_NS.equals(name.getNamespaceURI())) {
                    throw new InferenceException(
                            "sample " + sampleIndex + " contains gen: directives at " + currentPath(stack, name));
                }
                ShapeNode parent = stack.isEmpty() ? null : stack.peek();
                String xpath = (parent == null ? "" : parent.xpath()) + "/" + name.getLocalPart();
                ShapeNode node = new ShapeNode(name, xpath);
                if (parent == null) {
                    observation.rootName = name;
                    observation.root = node;
                } else {
                    parent.orderedContent().add(new ContentItem.ChildSlot(node));
                }
                absorbAttributes(node, se, sampleIndex);
                stack.push(node);
            } else if (event.isEndElement()) {
                depth--;
                stack.pop();
            } else if (event.isCharacters()) {
                Characters chars = event.asCharacters();
                if (!stack.isEmpty()) {
                    if (!chars.isWhiteSpace() && !chars.isIgnorableWhiteSpace()) {
                        stack.peek().valueSamples().add(chars.getData());
                    } else {
                        stack.peek().orderedContent().add(new ContentItem.Text(chars.getData()));
                    }
                }
            }
        }
        return observation;
    }

    private void absorbAttributes(ShapeNode node, StartElement se, int sampleIndex) {
        @SuppressWarnings("unchecked")
        Iterator<Attribute> it = se.getAttributes();
        while (it.hasNext()) {
            Attribute attr = it.next();
            QName qn = attr.getName();
            if (GEN_NS.equals(qn.getNamespaceURI())) {
                throw new InferenceException(
                        "sample " + sampleIndex + " contains gen: attribute " + qn + " at " + node.xpath());
            }
            node.attributes()
                    .computeIfAbsent(qn, k -> new ShapeNode.ValueSamples())
                    .add(attr.getValue());
        }
    }

    private ShapeNode mergeShapes() {
        // Task 10 fills in real merge; for Task 9 we just return the first sample's tree
        // augmented with sibling cardinality stats from each sample.
        ShapeNode root = samples.get(0).root;
        for (SampleObservation s : samples) {
            mergeInto(root, s.root);
        }
        return root;
    }

    private void mergeInto(ShapeNode dest, ShapeNode src) {
        if (dest == src) {
            // Same instance — count children for cardinality
            countChildCardinality(dest);
            return;
        }
        // Merge value samples
        for (Map.Entry<String, Integer> e : src.valueSamples().counts().entrySet()) {
            for (int i = 0; i < e.getValue(); i++) dest.valueSamples().add(e.getKey());
        }
        // Merge child slots by qName position
        Map<QName, ShapeNode> destChildren = new LinkedHashMap<>();
        for (ContentItem ci : dest.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                destChildren.putIfAbsent(cs.node().qName(), cs.node());
            }
        }
        Map<QName, Integer> srcChildCounts = new LinkedHashMap<>();
        for (ContentItem ci : src.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                srcChildCounts.merge(cs.node().qName(), 1, Integer::sum);
                ShapeNode existing = destChildren.get(cs.node().qName());
                if (existing == null) {
                    dest.orderedContent().add(cs);
                    destChildren.put(cs.node().qName(), cs.node());
                } else {
                    mergeInto(existing, cs.node());
                }
            }
        }
        for (Map.Entry<QName, Integer> e : srcChildCounts.entrySet()) {
            destChildren.get(e.getKey()).cardinalityPerParent().accept(e.getValue());
        }
    }

    private void countChildCardinality(ShapeNode node) {
        Map<QName, Integer> counts = new LinkedHashMap<>();
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                counts.merge(cs.node().qName(), 1, Integer::sum);
            }
        }
        for (Map.Entry<QName, Integer> e : counts.entrySet()) {
            // Find the merged child instance
            for (ContentItem ci : node.orderedContent()) {
                if (ci instanceof ContentItem.ChildSlot cs && cs.node().qName().equals(e.getKey())) {
                    cs.node().cardinalityPerParent().accept(e.getValue());
                    break;
                }
            }
        }
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) countChildCardinality(cs.node());
        }
    }

    private String currentPath(Deque<ShapeNode> stack, QName trailing) {
        StringBuilder sb = new StringBuilder();
        Iterator<ShapeNode> it = stack.descendingIterator();
        while (it.hasNext()) sb.append("/").append(it.next().qName().getLocalPart());
        sb.append("/").append(trailing.getLocalPart());
        return sb.toString();
    }

    private static class SampleObservation {
        QName rootName;
        ShapeNode root;
    }
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `mvn -Dtest=ShapeBuilderTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/ShapeBuilder.java \
        src/test/java/org/brylex/xmlgen/infer/ShapeBuilderTest.java
git commit -m "Add ShapeBuilder for single-sample tree construction with multi-sample merge"
```

---

### Task 10: Canonical cross-sample child order (frequency-mode)

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/infer/ShapeBuilder.java`
- Modify: `src/test/java/org/brylex/xmlgen/infer/ShapeBuilderTest.java`

- [ ] **Step 1: Add failing test for canonical order**

Append to `ShapeBuilderTest`:

```java
@Test
void canonicalOrderUsesFrequencyMode() throws Exception {
    // 3 samples: 2 with order (a,b), 1 with order (b,a) → canonical is (a,b)
    ShapeNode root = build(
            "<root><a/><b/></root>",
            "<root><a/><b/></root>",
            "<root><b/><a/></root>"
    );
    List<String> order = root.orderedContent().stream()
            .filter(ci -> ci instanceof ContentItem.ChildSlot)
            .map(ci -> ((ContentItem.ChildSlot) ci).node().qName().getLocalPart())
            .toList();
    assertThat(order).containsExactly("a", "b");
}

@Test
void canonicalOrderUsesLexTiebreakOnEqualFrequency() throws Exception {
    ShapeNode root = build(
            "<root><a/><b/></root>",
            "<root><b/><a/></root>"
    );
    List<String> order = root.orderedContent().stream()
            .filter(ci -> ci instanceof ContentItem.ChildSlot)
            .map(ci -> ((ContentItem.ChildSlot) ci).node().qName().getLocalPart())
            .toList();
    // Tiebreak: lex on qName-sequence → (a,b) wins over (b,a)
    assertThat(order).containsExactly("a", "b");
}

@Test
void inputOrderIndependence() throws Exception {
    ShapeNode forward = build(
            "<root><a/><b/></root>",
            "<root><b/><a/></root>"
    );
    ShapeNode reverse = build(
            "<root><b/><a/></root>",
            "<root><a/><b/></root>"
    );
    List<String> fwdOrder = childOrder(forward);
    List<String> revOrder = childOrder(reverse);
    assertThat(fwdOrder).isEqualTo(revOrder);
}

private List<String> childOrder(ShapeNode root) {
    return root.orderedContent().stream()
            .filter(ci -> ci instanceof ContentItem.ChildSlot)
            .map(ci -> ((ContentItem.ChildSlot) ci).node().qName().getLocalPart())
            .toList();
}
```

- [ ] **Step 2: Run, verify failure**

Run: `mvn -Dtest=ShapeBuilderTest test`
Expected: At least `canonicalOrderUsesFrequencyMode` and `inputOrderIndependence` fail.

- [ ] **Step 3: Replace merge with frequency-mode canonical order**

In `ShapeBuilder.mergeShapes()`, replace logic:

```java
private ShapeNode mergeShapes() {
    // 1. Collect all ordered child-qName sequences across samples (per node path)
    // 2. For each merged node, pick the most-frequent sequence; lex-tiebreak on qName list
    // 3. Build merged tree honoring that canonical order
    Map<String, List<List<QName>>> sequencesByXpath = new LinkedHashMap<>();
    for (SampleObservation obs : samples) {
        collectSequences(obs.root, sequencesByXpath);
    }
    // Build canonical
    return buildCanonical(samples.get(0).rootName, "/" + samples.get(0).rootName.getLocalPart(),
            sequencesByXpath);
}

private void collectSequences(ShapeNode node, Map<String, List<List<QName>>> out) {
    List<QName> seq = new ArrayList<>();
    for (ContentItem ci : node.orderedContent()) {
        if (ci instanceof ContentItem.ChildSlot cs) seq.add(cs.node().qName());
    }
    out.computeIfAbsent(node.xpath(), k -> new ArrayList<>()).add(seq);
    for (ContentItem ci : node.orderedContent()) {
        if (ci instanceof ContentItem.ChildSlot cs) collectSequences(cs.node(), out);
    }
}

private ShapeNode buildCanonical(QName rootName, String xpath,
                                 Map<String, List<List<QName>>> sequencesByXpath) {
    ShapeNode node = new ShapeNode(rootName, xpath);
    // Pick canonical child order
    List<List<QName>> seqs = sequencesByXpath.getOrDefault(xpath, List.of(List.of()));
    List<QName> canonical = pickCanonical(seqs);
    // For each child, recursively build (merging observations from all samples)
    for (QName childName : canonical) {
        String childXpath = xpath + "/" + childName.getLocalPart();
        ShapeNode child = buildCanonical(childName, childXpath, sequencesByXpath);
        node.orderedContent().add(new ContentItem.ChildSlot(child));
    }
    // Merge value samples, attributes, cardinalities from all sample observations at this xpath
    mergeObservationsInto(node, xpath);
    return node;
}

private List<QName> pickCanonical(List<List<QName>> seqs) {
    Map<List<QName>, Integer> counts = new LinkedHashMap<>();
    for (List<QName> s : seqs) counts.merge(s, 1, Integer::sum);
    return counts.entrySet().stream()
            .sorted((a, b) -> {
                int c = Integer.compare(b.getValue(), a.getValue());
                if (c != 0) return c;
                return compareSequences(a.getKey(), b.getKey());
            })
            .map(Map.Entry::getKey)
            .findFirst().orElse(List.of());
}

private int compareSequences(List<QName> a, List<QName> b) {
    int n = Math.min(a.size(), b.size());
    for (int i = 0; i < n; i++) {
        int c = a.get(i).getLocalPart().compareTo(b.get(i).getLocalPart());
        if (c != 0) return c;
    }
    return Integer.compare(a.size(), b.size());
}

private void mergeObservationsInto(ShapeNode dest, String xpath) {
    for (SampleObservation obs : samples) {
        ShapeNode src = findByXpath(obs.root, xpath);
        if (src == null) continue;
        // Merge values
        for (Map.Entry<String, Integer> e : src.valueSamples().counts().entrySet()) {
            for (int i = 0; i < e.getValue(); i++) dest.valueSamples().add(e.getKey());
        }
        // Merge attributes
        for (Map.Entry<QName, ShapeNode.ValueSamples> e : src.attributes().entrySet()) {
            ShapeNode.ValueSamples target = dest.attributes()
                    .computeIfAbsent(e.getKey(), k -> new ShapeNode.ValueSamples());
            for (Map.Entry<String, Integer> v : e.getValue().counts().entrySet()) {
                for (int i = 0; i < v.getValue(); i++) target.add(v.getKey());
            }
        }
        // Merge cardinality: count this node's siblings in the parent observation
        // (handled separately when walking parent's children — see Task 9 logic;
        //  here we just trust per-node cardinalityPerParent accumulated during walk)
    }
}

private ShapeNode findByXpath(ShapeNode node, String xpath) {
    if (node.xpath().equals(xpath)) return node;
    for (ContentItem ci : node.orderedContent()) {
        if (ci instanceof ContentItem.ChildSlot cs) {
            ShapeNode found = findByXpath(cs.node(), xpath);
            if (found != null) return found;
        }
    }
    return null;
}
```

Note: cardinality merge logic from Task 9 stays — keep accumulating during `absorb()` walk, but record per-sample sibling counts so we can re-feed them after canonicalization. Adjust `walk()` to record sibling counts in `SampleObservation`:

```java
private static class SampleObservation {
    QName rootName;
    ShapeNode root;
    Map<String, List<Integer>> childCountsByParentXpath = new LinkedHashMap<>();
}
```

And in `walk()`, after pushing a child onto its parent, increment a temporary counter per parent-xpath + child-qName key, flushing on EndElement.

(Implementation detail — keep total complexity low; the canonical builder consumes these counts to set `cardinalityPerParent` on merged children.)

- [ ] **Step 4: Run, verify pass**

Run: `mvn -Dtest=ShapeBuilderTest test`
Expected: All ShapeBuilder tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/ShapeBuilder.java \
        src/test/java/org/brylex/xmlgen/infer/ShapeBuilderTest.java
git commit -m "Canonicalize ShapeBuilder child order via frequency-mode + lex tiebreak"
```

---

### Task 11: ShapeBuilder mixed content + passthrough constructs + namespace handling

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/infer/ShapeBuilder.java`
- Modify: `src/test/java/org/brylex/xmlgen/infer/ShapeBuilderTest.java`

- [ ] **Step 1: Add failing tests for mixed content, namespaces, fail-loud cases**

Append to `ShapeBuilderTest`:

```java
@Test
void mixedContentIsMarked() throws Exception {
    ShapeNode root = build("<root><p>hello <b>world</b>!</p></root>");
    ShapeNode p = root.orderedContent().stream()
            .filter(ci -> ci instanceof ContentItem.ChildSlot)
            .map(ci -> ((ContentItem.ChildSlot) ci).node())
            .findFirst().orElseThrow();
    assertThat(p.hasMixedContent()).isTrue();
}

@Test
void defaultNamespaceIsPreserved() throws Exception {
    ShapeNode root = build("<order xmlns=\"http://schemas.com/v1\"><line/></order>");
    assertThat(root.qName().getNamespaceURI()).isEqualTo("http://schemas.com/v1");
}

@Test
void genNamespaceInSampleFailsLoud() {
    assertThatThrownBy(() -> build(
            "<order xmlns:gen=\"urn:xml:gen\"><line gen:repeat=\"3\"/></order>"
    )).isInstanceOf(InferenceException.class)
      .hasMessageContaining("gen:");
}

@Test
void disagreeingRootElementsFailLoud() {
    assertThatThrownBy(() -> build(
            "<order><line/></order>",
            "<invoice><line/></invoice>"
    )).isInstanceOf(InferenceException.class)
      .hasMessageContaining("disagree on root");
}
```

- [ ] **Step 2: Add mixed-content detection in `walk()`**

In `ShapeBuilder.walk()`, track per-element whether non-whitespace text co-occurs with child elements:

```java
// Inside walk(), at startElement push:
ElementState state = new ElementState(node);
elementStates.push(state);

// On Characters event:
if (event.isCharacters() && !chars.isWhiteSpace()) {
    state.hasNonWhitespaceText = true;
}
// On StartElement of child (depth++):
state.hasChildElements = true;

// On EndElement (depth--):
ElementState ended = elementStates.pop();
if (ended.hasNonWhitespaceText && ended.hasChildElements) {
    ended.node.markMixedContent();
}
```

Add an `ElementState` private class with `ShapeNode node`, `boolean hasNonWhitespaceText`, `boolean hasChildElements`.

- [ ] **Step 3: Add CDATA / PI / Comment as ContentItem entries**

In `walk()`, add branches:

```java
} else if (event instanceof javax.xml.stream.events.Comment c) {
    if (!stack.isEmpty()) stack.peek().orderedContent().add(new ContentItem.Comment(c.getText()));
} else if (event.isProcessingInstruction()) {
    javax.xml.stream.events.ProcessingInstruction pi =
            (javax.xml.stream.events.ProcessingInstruction) event;
    if (!stack.isEmpty()) stack.peek().orderedContent().add(
            new ContentItem.ProcessingInstruction(pi.getTarget(), pi.getData()));
}
// CDATA arrives as a Characters event where chars.isCData() == true — handle separately:
if (event.isCharacters() && event.asCharacters().isCData()) {
    if (!stack.isEmpty()) stack.peek().orderedContent().add(
            new ContentItem.Cdata(event.asCharacters().getData()));
    continue;
}
```

- [ ] **Step 4: Add `xmlns:gen` URI-conflict check on root**

In `walk()`, when processing the StartElement of the document root, check namespaces:

```java
@SuppressWarnings("unchecked")
Iterator<Namespace> ns = se.getNamespaces();
while (ns.hasNext()) {
    Namespace n = ns.next();
    if ("gen".equals(n.getPrefix()) && !GEN_NS.equals(n.getNamespaceURI())) {
        throw new InferenceException(
                "sample " + sampleIndex + " declares xmlns:gen with conflicting URI: "
                        + n.getNamespaceURI());
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `mvn -Dtest=ShapeBuilderTest test`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/ShapeBuilder.java \
        src/test/java/org/brylex/xmlgen/infer/ShapeBuilderTest.java
git commit -m "ShapeBuilder handles mixed content, namespaces, passthrough constructs"
```

---

## Phase 4 — Analyzers

### Task 12: `Analyzer` interface + `AnalyzerPipeline` + `AnalysisContext`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/Analyzer.java`
- Create: `src/main/java/org/brylex/xmlgen/infer/AnalysisContext.java`
- Create: `src/main/java/org/brylex/xmlgen/infer/AnalyzerPipeline.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/AnalyzerPipelineTest.java`

- [ ] **Step 1: Write the failing test**

```java
// AnalyzerPipelineTest.java
package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerPipelineTest {

    @Test
    void runsAnalyzersInOrderAndCollectsWarnings() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        Analyzer first = (n, ctx) -> ctx.warn(new InferenceWarning.AmbiguousOrder(
                "/order", "(a,b)", "(b,a)"));
        Analyzer second = (n, ctx) -> {};
        AnalyzerPipeline pipeline = new AnalyzerPipeline(InferenceConfig.defaults(),
                List.of(first, second));
        List<InferenceWarning> warnings = pipeline.run(root);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).isInstanceOf(InferenceWarning.AmbiguousOrder.class);
    }
}
```

- [ ] **Step 2: Create interface, context, pipeline**

```java
// Analyzer.java
package org.brylex.xmlgen.infer;

@FunctionalInterface
public interface Analyzer {
    void analyze(ShapeNode root, AnalysisContext ctx);
}
```

```java
// AnalysisContext.java
package org.brylex.xmlgen.infer;

import java.util.ArrayList;
import java.util.List;

public class AnalysisContext {

    private final InferenceConfig config;
    private final List<InferenceWarning> warnings = new ArrayList<>();

    public AnalysisContext(InferenceConfig config) {
        this.config = config;
    }

    public InferenceConfig config() { return config; }

    public void warn(InferenceWarning warning) {
        if (config.strictMode()) {
            throw new InferenceException("strict mode: " + warning.message(),
                    List.of(warning));
        }
        warnings.add(warning);
    }

    public List<InferenceWarning> warnings() {
        return List.copyOf(warnings);
    }
}
```

```java
// AnalyzerPipeline.java
package org.brylex.xmlgen.infer;

import java.util.List;

public class AnalyzerPipeline {

    private final InferenceConfig config;
    private final List<Analyzer> analyzers;

    public AnalyzerPipeline(InferenceConfig config, List<Analyzer> analyzers) {
        this.config = config;
        this.analyzers = List.copyOf(analyzers);
    }

    public List<InferenceWarning> run(ShapeNode root) {
        AnalysisContext ctx = new AnalysisContext(config);
        for (Analyzer a : analyzers) {
            a.analyze(root, ctx);
        }
        return ctx.warnings();
    }
}
```

- [ ] **Step 3: Run, verify pass**

Run: `mvn -Dtest=AnalyzerPipelineTest test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/Analyzer.java \
        src/main/java/org/brylex/xmlgen/infer/AnalysisContext.java \
        src/main/java/org/brylex/xmlgen/infer/AnalyzerPipeline.java \
        src/test/java/org/brylex/xmlgen/infer/AnalyzerPipelineTest.java
git commit -m "Add Analyzer interface, AnalysisContext, AnalyzerPipeline"
```

---

### Task 13: `RepeatAnalyzer`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/analyzers/RepeatAnalyzer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/analyzers/RepeatAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// RepeatAnalyzerTest.java
package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatAnalyzerTest {

    @Test
    void variableCardinalityProducesRangeDirective() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.cardinalityPerParent().accept(2);
        line.cardinalityPerParent().accept(5);
        line.cardinalityPerParent().accept(3);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(line.directive()).contains(new Directive.Repeat(2, 5));
    }

    @Test
    void fixedCardinalityProducesFixedRepeat() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.cardinalityPerParent().accept(3);
        line.cardinalityPerParent().accept(3);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(line.directive()).contains(new Directive.Repeat(3, 3));
    }

    @Test
    void singletonCardinalityIsNoOp() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.cardinalityPerParent().accept(1);
        line.cardinalityPerParent().accept(1);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(line.directive()).isEmpty();
    }
}
```

- [ ] **Step 2: Implement `RepeatAnalyzer`**

```java
// RepeatAnalyzer.java
package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;

public class RepeatAnalyzer implements Analyzer {

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root, ctx);
    }

    private void walk(ShapeNode node, AnalysisContext ctx) {
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                ShapeNode child = cs.node();
                long count = child.cardinalityPerParent().getCount();
                if (count > 0) {
                    int min = (int) child.cardinalityPerParent().getMin();
                    int max = (int) child.cardinalityPerParent().getMax();
                    if (max > 1) {
                        child.setDirective(new Directive.Repeat(min, max));
                    }
                }
                walk(child, ctx);
            }
        }
    }
}
```

- [ ] **Step 3: Run, verify pass**

Run: `mvn -Dtest=RepeatAnalyzerTest test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/analyzers/RepeatAnalyzer.java \
        src/test/java/org/brylex/xmlgen/infer/analyzers/RepeatAnalyzerTest.java
git commit -m "Add RepeatAnalyzer"
```

---

### Task 14: `RandomRangeAnalyzer` (numeric, decimal, date, UUID)

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/analyzers/RandomRangeAnalyzer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/analyzers/RandomRangeAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// RandomRangeAnalyzerTest.java
package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RandomRangeAnalyzerTest {

    @Test
    void detectsIntRange() {
        ShapeNode root = leaf("qty", "1", "5", "3", "7", "2");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode qty = childOf(root, "qty");
        assertThat(qty.directive()).contains(
                new Directive.Random(new Range.IntRange(1, 7)));
    }

    @Test
    void detectsAmountRange() {
        ShapeNode root = leaf("amount", "1.00", "1.50", "2.25", "0.75", "3.00");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode amount = childOf(root, "amount");
        assertThat(amount.directive()).contains(new Directive.Random(
                new Range.AmountRange(new BigDecimal("0.75"), new BigDecimal("3.00"), 2)));
    }

    @Test
    void detectsDateRange() {
        ShapeNode root = leaf("when", "2024-01-01", "2024-06-15", "2024-12-31", "2024-03-10", "2024-09-30");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode when = childOf(root, "when");
        assertThat(when.directive()).contains(new Directive.Random(
                new Range.DateRange(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"))));
    }

    @Test
    void detectsUuidFormat() {
        ShapeNode root = leaf("id",
                "550e8400-e29b-41d4-a716-446655440000",
                "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "7a8b9c0d-1e2f-43a4-b5c6-d7e8f9a0b1c2",
                "0123abcd-4567-4abc-89ab-cdef01234567");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode id = childOf(root, "id");
        assertThat(id.directive()).contains(new Directive.Random(new Range.Uuid()));
    }

    @Test
    void mixedTypesEmitsWarning() {
        ShapeNode root = leaf("mix", "1", "two", "3", "four", "5");
        AnalysisContext ctx = new AnalysisContext(InferenceConfig.defaults());
        new RandomRangeAnalyzer().analyze(root, ctx);
        ShapeNode mix = childOf(root, "mix");
        assertThat(mix.directive()).isEmpty();
        assertThat(ctx.warnings()).anyMatch(w -> w instanceof InferenceWarning.MixedTypeFallback);
    }

    private ShapeNode leaf(String name, String... values) {
        ShapeNode root = new ShapeNode(new QName("root"), "/root");
        ShapeNode child = new ShapeNode(new QName(name), "/root/" + name);
        root.orderedContent().add(new ContentItem.ChildSlot(child));
        for (String v : values) child.valueSamples().add(v);
        return root;
    }

    private ShapeNode childOf(ShapeNode parent, String name) {
        return parent.orderedContent().stream()
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> ((ContentItem.ChildSlot) ci).node())
                .filter(n -> n.qName().getLocalPart().equals(name))
                .findFirst().orElseThrow();
    }
}
```

- [ ] **Step 2: Implement `RandomRangeAnalyzer`**

```java
// RandomRangeAnalyzer.java
package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public class RandomRangeAnalyzer implements Analyzer {

    private static final Pattern UUID_RX = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root, ctx);
    }

    private void walk(ShapeNode node, AnalysisContext ctx) {
        if (node.directive().isEmpty() && !node.valueSamples().isEmpty()) {
            tryAssign(node, ctx);
        }
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) walk(cs.node(), ctx);
        }
    }

    private void tryAssign(ShapeNode node, AnalysisContext ctx) {
        InferenceConfig cfg = ctx.config();
        int total = node.valueSamples().total();
        int distinct = node.valueSamples().distinctCount();
        double ratio = (double) distinct / total;
        if (ratio < cfg.randomCardinalityThreshold()) {
            return; // Too few distinct values to bother with random-range
        }

        // UUID detection first (high specificity)
        if (node.valueSamples().distinct().stream().allMatch(v -> UUID_RX.matcher(v).matches())) {
            node.setDirective(new Directive.Random(new Range.Uuid()));
            return;
        }

        // Integer detection
        try {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (String v : node.valueSamples().distinct()) {
                long l = Long.parseLong(v);
                if (l < min) min = l;
                if (l > max) max = l;
            }
            node.setDirective(new Directive.Random(new Range.IntRange(min, max)));
            return;
        } catch (NumberFormatException ignored) {}

        // Decimal detection
        try {
            BigDecimal min = null, max = null;
            int scale = 0;
            for (String v : node.valueSamples().distinct()) {
                BigDecimal d = new BigDecimal(v);
                scale = Math.max(scale, d.scale());
                if (min == null || d.compareTo(min) < 0) min = d;
                if (max == null || d.compareTo(max) > 0) max = d;
            }
            node.setDirective(new Directive.Random(new Range.AmountRange(min, max, scale)));
            return;
        } catch (NumberFormatException ignored) {}

        // ISO date detection
        try {
            LocalDate min = null, max = null;
            for (String v : node.valueSamples().distinct()) {
                LocalDate d = LocalDate.parse(v);
                if (min == null || d.isBefore(min)) min = d;
                if (max == null || d.isAfter(max)) max = d;
            }
            node.setDirective(new Directive.Random(new Range.DateRange(min, max)));
            return;
        } catch (DateTimeParseException ignored) {}

        // Mixed types — emit warning and leave directive unset
        ctx.warn(new InferenceWarning.MixedTypeFallback(node.xpath(),
                "could not parse all values as int/decimal/date/uuid"));
    }
}
```

- [ ] **Step 3: Run, verify pass**

Run: `mvn -Dtest=RandomRangeAnalyzerTest test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/analyzers/RandomRangeAnalyzer.java \
        src/test/java/org/brylex/xmlgen/infer/analyzers/RandomRangeAnalyzerTest.java
git commit -m "Add RandomRangeAnalyzer with int/decimal/date/UUID detection"
```

---

### Task 15: `IncrementAnalyzer`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/analyzers/IncrementAnalyzer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/analyzers/IncrementAnalyzerTest.java`

(Mønster identisk med RepeatAnalyzer: TDD-test → impl → kjør → commit. Detaljen er
at IncrementAnalyzer trenger access til hver sample's *sekvens* av verdier per
parent-instans for å detektere konstant step. Det er ikke fanget av ShapeNode i
nåværende form. Plan: utvid ShapeNode med `List<List<String>> valueSequencesPerRepeatIteration`
ved Task 9-walk, populer kun for noder som har repeat-merket parent. Eller:
IncrementAnalyzer rekjører en walk over `samples`-listen — men da må samples-data
være tilgjengelig på AnalysisContext. Enkleste: AnalysisContext får tilgang til
en `Map<String, List<List<String>>>` med iterations-sekvenser per xpath, populert
av ShapeBuilder.)

- [ ] **Step 1: Write failing test** (samme mønster som RepeatAnalyzerTest, men med
sample-vise sekvenser fra AnalysisContext)

- [ ] **Step 2: Extend `AnalysisContext` med `Map<String, List<List<String>>> repeatIterationValues`**
populert via ny `AnalysisContext.recordIteration(String xpath, List<String> values)`
som ShapeBuilder kaller etter at hver gen:repeat-parent er ferdig walket

- [ ] **Step 3: Implement IncrementAnalyzer** — for hver node med repeat-merked parent,
hent iterations-sekvenser; sjekk om alle er monoton-increasing med samme step

- [ ] **Step 4: Run + commit**

```bash
git commit -m "Add IncrementAnalyzer"
```

---

### Task 16: `ChooseAnalyzer`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/analyzers/ChooseAnalyzer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/analyzers/ChooseAnalyzerTest.java`

- [ ] **Step 1: Write failing tests for:**
  - Single signature → no-op
  - Subset chain (A ⊂ B) → optional element wrapping
  - Mutually distinct signatures → real gen:choose with branches
  - <10% signature → dropped + `DroppedRareSignature` warning
  - > 4 branches → fallback to majority + `ChooseBranchOverflow` warning

(Test-stub for distinkte signaturer:)

```java
@Test
void mutuallyDistinctSignaturesProduceChoose() {
    ShapeNode root = new ShapeNode(new QName("entry"), "/entry");
    // signature 1 (count 7): line with {sku, qty}
    // signature 2 (count 3): line with {promo, discount}
    // ChooseAnalyzer should produce Choose with 2 branches
    populateSignatureGroups(root, /* ... */);

    new ChooseAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

    assertThat(root.directive()).isPresent();
    assertThat(root.directive().get()).isInstanceOf(Directive.Choose.class);
    Directive.Choose choose = (Directive.Choose) root.directive().get();
    assertThat(choose.branches()).hasSize(2);
}
```

- [ ] **Step 2: Implement ChooseAnalyzer**

```java
// ChooseAnalyzer.java
package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;
import javax.xml.namespace.QName;
import java.util.*;

public class ChooseAnalyzer implements Analyzer {

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        // Iterate; each parent contributes its observed child-signatures
        // (stored in AnalysisContext.parentSignatures(xpath) by ShapeBuilder)
        walk(root, ctx);
    }

    private void walk(ShapeNode node, AnalysisContext ctx) {
        List<Signature> signatures = ctx.signaturesAt(node.xpath());
        if (signatures != null && !signatures.isEmpty()) {
            classify(node, signatures, ctx);
        }
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) walk(cs.node(), ctx);
        }
    }

    private void classify(ShapeNode node, List<Signature> sigs, AnalysisContext ctx) {
        Map<Signature, Integer> counts = new LinkedHashMap<>();
        for (Signature s : sigs) counts.merge(s, 1, Integer::sum);
        int total = sigs.size();

        // Drop signatures < noiseThreshold
        double noise = ctx.config().chooseNoiseThreshold();
        List<Map.Entry<Signature, Integer>> kept = new ArrayList<>();
        for (Map.Entry<Signature, Integer> e : counts.entrySet()) {
            double ratio = (double) e.getValue() / total;
            if (ratio < noise) {
                ctx.warn(new InferenceWarning.DroppedRareSignature(
                        node.xpath(), e.getKey().repr(), e.getValue(), total));
            } else {
                kept.add(e);
            }
        }

        if (kept.size() <= 1) return;

        // Check subset-chain: is one signature a strict prefix-by-subset of another?
        if (isSubsetChain(kept)) {
            // For each "extra" element in the larger signature, wrap in choose with empty branch
            // (Detailed implementation: walk children added in larger sig, set their directive
            //  to Choose with one branch containing the element + one empty branch)
            applySubsetChain(node, kept, ctx);
            return;
        }

        // Mutually distinct — emit Choose at node level
        if (kept.size() > ctx.config().chooseMaxBranches()) {
            ctx.warn(new InferenceWarning.ChooseBranchOverflow(
                    node.xpath(), kept.size(), ctx.config().chooseMaxBranches()));
            // Keep top-N branches by count
            kept.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            kept = kept.subList(0, ctx.config().chooseMaxBranches());
        }

        List<Directive.Branch> branches = new ArrayList<>();
        for (Map.Entry<Signature, Integer> e : kept) {
            branches.add(new Directive.Branch(e.getValue(), e.getKey().bodyFor(node)));
        }
        node.setDirective(new Directive.Choose(branches));
    }

    // ... isSubsetChain, applySubsetChain helpers
}
```

(Note: requires `AnalysisContext.signaturesAt(xpath)` — populated by ShapeBuilder
in Task 11 extension. Add to plan-time: extend ShapeBuilder to record per-parent
child signatures into AnalysisContext during the build phase OR change
TemplateInferrer wiring to pass observation data through.)

`Signature` is a record holding the ordered (QName, cardBucket) list:

```java
public record Signature(List<Pair> parts) {
    public record Pair(QName qName, int cardBucket) {}
    // cardBucket: 0, 1, or 2 (representing "0", "1", "2+")
    public String repr() {
        StringBuilder sb = new StringBuilder("[");
        for (Pair p : parts) sb.append(p.qName().getLocalPart()).append(":").append(p.cardBucket()).append(",");
        sb.append("]");
        return sb.toString();
    }
    public List<ContentItem> bodyFor(ShapeNode parent) {
        // Filter parent.orderedContent() to only include children appearing in this signature
        // Implementation detail
    }
}
```

Place `Signature` in same package as Directive.

- [ ] **Step 3: Run + commit**

```bash
git commit -m "Add ChooseAnalyzer with subset-chain, distinct, and noise handling"
```

---

### Task 17: `PickCoherenceAnalyzer`

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/analyzers/PickCoherenceAnalyzer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/analyzers/PickCoherenceAnalyzerTest.java`

- [ ] **Step 1: Write failing tests for:**
  - Low-cardinality field with bijection + ≥ K rows → shared pool
  - Low-cardinality field below K → individual pick or first-observed
  - Cardinality = 1 → first-observed literal
  - All-unique values → pool with `UniqueValueCycleRisk` warning
  - Partial bijection → individual picks + `PartialBijectionRejected` warning
  - Pool name uses path from nearest gen:repeat anchor
  - Pool rows sorted lex on first column

```java
@Test
void bijectiveLowCardinalityProducesSharedPool() {
    // Setup: <line gen:repeat> with <party><name> and <party><iban> children,
    // observed pairs [(Acme, NO11), (Beta, NO22), (Gamma, NO33), (Delta, NO44), (Epsilon, NO55)]
    ShapeNode line = setupCoherentSamples(/* ... */);
    AnalysisContext ctx = new AnalysisContext(InferenceConfig.defaults());
    // Pre-mark line as Repeat (so it's an anchor)
    line.setDirective(new Directive.Repeat(2, 5));

    new PickCoherenceAnalyzer().analyze(/* root */, ctx);

    ShapeNode name = findByXpath("/order/line/party/name");
    ShapeNode iban = findByXpath("/order/line/party/iban");
    assertThat(name.directive()).contains(new Directive.Pick("line.party", "name"));
    assertThat(iban.directive()).contains(new Directive.Pick("line.party", "iban"));
}
```

- [ ] **Step 2: Implement PickCoherenceAnalyzer**

Algorithm sketched in spec. Key dependencies:
- Need access to per-parent-instance value tuples (which sample, which iteration, which sibling-leaves had which values) — from `AnalysisContext.iterationValuesAt(parentXpath)`
- Need to know which ancestor (if any) is `Directive.Repeat`-marked — walk up the tree
- Pool name computed from path between gen:repeat-anchor and parent-of-leaf

- [ ] **Step 3: Run + commit**

```bash
git commit -m "Add PickCoherenceAnalyzer with bijection, K-threshold, and lex-sorted pool rows"
```

---

## Phase 5 — TemplateRenderer

### Task 18: `TemplateRenderer` for strukturerte elementer

**Files:**
- Create: `src/main/java/org/brylex/xmlgen/infer/TemplateRenderer.java`
- Test: `src/test/java/org/brylex/xmlgen/infer/TemplateRendererTest.java`

- [ ] **Step 1: Write failing test**

```java
// TemplateRendererTest.java
package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pools;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    @Test
    void rendersEmptyRoot() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        TemplateRenderer.Result r = new TemplateRenderer().render(root);
        assertThat(r.xml()).contains("<order");
        assertThat(r.xml()).contains("xmlns:gen=\"urn:xml:gen\"");
        assertThat(r.pools().isEmpty()).isTrue();
    }

    @Test
    void rendersRepeatDirective() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.setDirective(new Directive.Repeat(2, 5));

        String xml = new TemplateRenderer().render(root).xml();

        assertThat(xml).contains("gen:repeat=\"2..5\"");
    }

    @Test
    void rendersFixedRepeatWithoutRange() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.setDirective(new Directive.Repeat(3, 3));

        String xml = new TemplateRenderer().render(root).xml();

        assertThat(xml).contains("gen:repeat=\"3\"");
        assertThat(xml).doesNotContain("3..3");
    }

    @Test
    void rendersPickAddsPoolToPools() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode sku = new ShapeNode(new QName("sku"), "/order/sku");
        root.orderedContent().add(new ContentItem.ChildSlot(sku));
        sku.valueSamples().add("A");
        sku.valueSamples().add("B");
        sku.valueSamples().add("C");
        sku.setDirective(new Directive.Pick("line", "sku"));

        TemplateRenderer.Result r = new TemplateRenderer().render(root);
        assertThat(r.xml()).contains("gen:pick=\"line/sku\"");
        assertThat(r.pools().get("line").rows()).hasSize(3);
    }
}
```

- [ ] **Step 2: Implement `TemplateRenderer`**

```java
// TemplateRenderer.java
package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pool;
import org.brylex.xmlgen.Pools;

import javax.xml.namespace.QName;
import java.util.*;

public class TemplateRenderer {

    public record Result(String xml, Pools pools) {}

    public Result render(ShapeNode root) {
        StringBuilder sb = new StringBuilder();
        Pools.Builder poolBuilder = Pools.builder();
        Map<String, List<Map<String, String>>> pendingPoolRows = new LinkedHashMap<>();

        renderNode(root, sb, 0, true, poolBuilder, pendingPoolRows);

        // Flush pool rows into builder (sorted lex on first column)
        for (Map.Entry<String, List<Map<String, String>>> e : pendingPoolRows.entrySet()) {
            List<Map<String, String>> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparing(m -> {
                // Sort by first column value
                if (m.isEmpty()) return "";
                return m.values().iterator().next();
            }));
            poolBuilder.inline(e.getKey(), rows);
        }
        return new Result(sb.toString(), poolBuilder.build());
    }

    private void renderNode(ShapeNode node, StringBuilder sb, int indent, boolean isRoot,
                            Pools.Builder pools,
                            Map<String, List<Map<String, String>>> pendingPoolRows) {
        indent(sb, indent);
        sb.append("<").append(qNameToString(node.qName()));

        if (isRoot) {
            sb.append(" xmlns:gen=\"urn:xml:gen\"");
        }

        // Attributes (first-observed for variable, verbatim for constants)
        for (Map.Entry<QName, ShapeNode.ValueSamples> e : node.attributes().entrySet()) {
            String value = e.getValue().counts().keySet().iterator().next();
            sb.append(" ").append(qNameToString(e.getKey())).append("=\"")
                    .append(escape(value)).append("\"");
        }

        // Directive attribute (if applicable)
        node.directive().ifPresent(d -> writeDirective(sb, d, node, pendingPoolRows));

        if (node.orderedContent().isEmpty() && node.valueSamples().isEmpty()) {
            sb.append("/>\n");
            return;
        }
        sb.append(">");

        if (node.hasMixedContent()) {
            // Render verbatim from first observation
            renderVerbatim(node, sb);
        } else {
            sb.append("\n");
            for (ContentItem ci : node.orderedContent()) {
                if (ci instanceof ContentItem.ChildSlot cs) {
                    renderNode(cs.node(), sb, indent + 2, false, pools, pendingPoolRows);
                } else if (ci instanceof ContentItem.Comment c) {
                    indent(sb, indent + 2);
                    sb.append("<!-- ").append(c.value()).append(" -->\n");
                }
                // Other ContentItem types: passthrough as needed
            }
            // If this is a leaf with valueSamples and no children
            if (!node.valueSamples().isEmpty() && node.orderedContent().isEmpty()) {
                sb.append(firstObservedOrPlaceholder(node));
            }
            indent(sb, indent);
        }
        sb.append("</").append(qNameToString(node.qName())).append(">\n");
    }

    private void writeDirective(StringBuilder sb, Directive d, ShapeNode node,
                                Map<String, List<Map<String, String>>> pendingPoolRows) {
        switch (d) {
            case Directive.Repeat r -> {
                if (r.isFixed()) {
                    sb.append(" gen:repeat=\"").append(r.min()).append("\"");
                } else {
                    sb.append(" gen:repeat=\"").append(r.min()).append("..").append(r.max()).append("\"");
                }
            }
            case Directive.Increment i -> sb.append(" gen:increment=\"").append(i.step()).append("\"");
            case Directive.Random rnd -> writeRandom(sb, rnd.range());
            case Directive.Pick p -> {
                sb.append(" gen:pick=\"").append(p.poolName()).append("/").append(p.column()).append("\"");
                // Stash row data
                Map<String, String> row = new LinkedHashMap<>();
                for (String v : node.valueSamples().distinct()) {
                    row.put(p.column(), v);
                    pendingPoolRows.computeIfAbsent(p.poolName(), k -> new ArrayList<>()).add(Map.copyOf(row));
                    row.clear();
                }
            }
            case Directive.Choose c -> {
                // Choose is rendered as a wrapping element — not an attribute
                // Caller should handle this case before writing the opening tag
                throw new IllegalStateException("Choose directive must be handled by writeChooseWrapper");
            }
        }
    }

    private void writeRandom(StringBuilder sb, Range range) {
        switch (range) {
            case Range.IntRange r -> sb.append(" gen:random-int=\"").append(r.min()).append("..").append(r.max()).append("\"");
            case Range.AmountRange r -> sb.append(" gen:random-amount=\"")
                    .append(r.min().toPlainString()).append("..").append(r.max().toPlainString()).append("\"");
            case Range.DateRange r -> sb.append(" gen:random-date=\"").append(r.min()).append("..").append(r.max()).append("\"");
            case Range.Uuid u -> sb.append(" gen:random-uuid=\"true\"");
        }
    }

    private String firstObservedOrPlaceholder(ShapeNode node) {
        if (node.valueSamples().isEmpty()) return "_";
        if (node.directive().isPresent()) return "_";
        return escape(node.valueSamples().distinct().iterator().next());
    }

    private void renderVerbatim(ShapeNode node, StringBuilder sb) {
        // For Task 19: render first-sample content as-is. For now, simplified.
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.Text t) sb.append(t.value());
            // ... etc
        }
    }

    private void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append(' ');
    }

    private String qNameToString(QName q) {
        if (q.getPrefix() == null || q.getPrefix().isEmpty()) return q.getLocalPart();
        return q.getPrefix() + ":" + q.getLocalPart();
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
git add src/main/java/org/brylex/xmlgen/infer/TemplateRenderer.java \
        src/test/java/org/brylex/xmlgen/infer/TemplateRendererTest.java
git commit -m "Add TemplateRenderer for structured elements with directive emission"
```

---

### Task 19: TemplateRenderer for mixed-content, choose wrapping, WARNING-kommentar

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/infer/TemplateRenderer.java`
- Modify: `src/test/java/org/brylex/xmlgen/infer/TemplateRendererTest.java`

- [ ] **Step 1: Add failing tests for:**
  - Mixed-content node renders verbatim (no re-indent)
  - `Directive.Choose` wraps with `<gen:choose>` + `<gen:when>` per branch
  - All-unique pick adds WARNING-kommentar above the pick element

- [ ] **Step 2: Implement these in TemplateRenderer**

For Choose: detect at `renderNode` entry whether `directive` is Choose; if so, emit `<gen:choose>` wrapper around branches instead of normal element.

For WARNING: track in `pendingPoolRows` which pools came from all-unique observation sets; emit `<!-- WARNING: ... -->` just before the pick element.

- [ ] **Step 3: Run + commit**

```bash
git commit -m "Render mixed content verbatim, choose wrapping, and unique-value warnings"
```

---

## Phase 6 — End-to-end wiring

### Task 20: Koble `TemplateInferrer.infer()` til faktisk pipeline

**Files:**
- Modify: `src/main/java/org/brylex/xmlgen/infer/TemplateInferrer.java`
- Modify: `src/test/java/org/brylex/xmlgen/infer/TemplateInferrerStubTest.java` (utvid)

- [ ] **Step 1: Write a small end-to-end test**

```java
@Test
void endToEndProducesValidTemplateAndPools() throws Exception {
    XMLEventReader s1 = parse("""
            <order>
                <line><sku>X1</sku><qty>2</qty></line>
                <line><sku>X2</sku><qty>3</qty></line>
            </order>
            """);
    XMLEventReader s2 = parse("""
            <order>
                <line><sku>X1</sku><qty>5</qty></line>
                <line><sku>X3</sku><qty>7</qty></line>
                <line><sku>X1</sku><qty>1</qty></line>
            </order>
            """);
    InferredTemplate t = TemplateInferrer.infer(List.of(s1, s2));

    // Template parses
    XMLEventReader expanded = t.expand(new Random(42));
    while (expanded.hasNext()) expanded.next(); // shouldn't throw

    // Template contains expected directives
    assertThat(t.templateXml()).contains("gen:repeat=\"2..3\"");
    assertThat(t.templateXml()).contains("gen:random-int=\"1..7\"");
}
```

- [ ] **Step 2: Wire up TemplateInferrer**

```java
public static InferredTemplate infer(List<XMLEventReader> samples, InferenceConfig config) {
    if (samples.isEmpty()) {
        throw new InferenceException("at least one sample required");
    }

    ShapeBuilder builder = new ShapeBuilder(config);
    for (int i = 0; i < samples.size(); i++) {
        try {
            builder.absorb(samples.get(i));
        } catch (XMLStreamException e) {
            throw new InferenceException("sample " + (i + 1) + " failed to parse", e);
        }
    }
    ShapeNode root = builder.build();

    AnalyzerPipeline pipeline = new AnalyzerPipeline(config, List.of(
            new RepeatAnalyzer(),
            new IncrementAnalyzer(),
            new RandomRangeAnalyzer(),
            new ChooseAnalyzer(),
            new PickCoherenceAnalyzer()
    ));
    // Note: pipeline.run() also needs the per-iteration / per-signature data
    // accumulated by ShapeBuilder. Pass via AnalysisContext.
    List<InferenceWarning> warnings = pipeline.run(root, builder.observationData());

    TemplateRenderer.Result result = new TemplateRenderer().render(root);
    return new InferredTemplate(result.xml(), result.pools(), warnings);
}
```

(`AnalyzerPipeline.run(root, observationData)` overload accepts the per-xpath
data ShapeBuilder collected. `builder.observationData()` returns a record
holding the iteration-value-sequences and parent-signature-lists.)

- [ ] **Step 3: Run + commit**

```bash
git commit -m "Wire TemplateInferrer pipeline end-to-end"
```

---

## Phase 7 — Acceptance-suite

### Task 21: Tier A property-fixture: typical-invoice

**Files:**
- Create: `src/test/resources/infer/tier-a/typical-invoice/sample-1.xml` (and -2 through -8)
- Create: `src/test/java/org/brylex/xmlgen/infer/TemplateInferrerTest.java`

- [ ] **Step 1: Write 8 typical-invoice samples**

Each sample varies line counts, quantities, dates, etc. Example sample-1.xml:

```xml
<invoice>
    <header>
        <number>2024-001</number>
        <issued>2024-01-15</issued>
        <customer>
            <name>Acme AS</name>
            <orgnr>123456789</orgnr>
        </customer>
    </header>
    <line>
        <sku>X1</sku>
        <qty>3</qty>
        <price>199.00</price>
    </line>
    <line>
        <sku>Y2</sku>
        <qty>1</qty>
        <price>49.50</price>
    </line>
</invoice>
```

Sample-2 has 4 lines, sample-3 has 1 line, etc. Different customers, different SKUs (≥ 5 distinct across all samples), different dates within 2024-01-01..2024-12-31.

- [ ] **Step 2: Write property-only acceptance test**

```java
// TemplateInferrerTest.java
package org.brylex.xmlgen.infer;

import org.dom4j.Document;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateInferrerTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void typicalInvoiceFixture() throws Exception {
        List<XMLEventReader> samples = loadSamples("infer/tier-a/typical-invoice", 8);
        InferredTemplate t = TemplateInferrer.infer(samples);

        // (a) XPath dekning
        Set<String> sampleXPaths = unionXPaths("infer/tier-a/typical-invoice", 8);
        Set<String> templateXPaths = extractXPaths(t.templateXml());
        assertThat(templateXPaths).containsAll(sampleXPaths);

        // (b) Direktiv-spesifikke positions
        assertThat(t.templateXml()).containsPattern("<line[^>]*gen:repeat");
        assertThat(t.templateXml()).containsPattern("<qty[^>]*gen:random-int");
        assertThat(t.templateXml()).containsPattern("<price[^>]*gen:random-amount");
        assertThat(t.templateXml()).containsPattern("<issued[^>]*gen:random-date");

        // (c) Verdi-grense-bevaring under 100 ekspansjoner med seedet Random
        long minObservedQty = Long.MAX_VALUE, maxObservedQty = Long.MIN_VALUE;
        for (int i = 0; i < 100; i++) {
            XMLEventReader expanded = t.expand(new java.util.Random(42 + i));
            Document doc = new STAXEventReader().readDocument(expanded);
            @SuppressWarnings("unchecked")
            List<org.dom4j.Node> qtys = doc.selectNodes("//qty");
            for (org.dom4j.Node n : qtys) {
                long qty = Long.parseLong(n.getText());
                minObservedQty = Math.min(minObservedQty, qty);
                maxObservedQty = Math.max(maxObservedQty, qty);
            }
        }
        // Asserter at observed range ligger innenfor sample range
        // (sample-set har qty mellom 1 og 10)
        assertThat(minObservedQty).isGreaterThanOrEqualTo(1);
        assertThat(maxObservedQty).isLessThanOrEqualTo(10);
    }

    private List<XMLEventReader> loadSamples(String resourceDir, int count) throws Exception {
        List<XMLEventReader> readers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            InputStream is = getClass().getClassLoader()
                    .getResourceAsStream(resourceDir + "/sample-" + i + ".xml");
            readers.add(FACTORY.createXMLEventReader(is));
        }
        return readers;
    }

    private Set<String> unionXPaths(String resourceDir, int count) throws Exception {
        Set<String> all = new LinkedHashSet<>();
        for (int i = 1; i <= count; i++) {
            InputStream is = getClass().getClassLoader()
                    .getResourceAsStream(resourceDir + "/sample-" + i + ".xml");
            Document doc = new STAXEventReader().readDocument(FACTORY.createXMLEventReader(is));
            collectXPaths(doc.getRootElement(), "/" + doc.getRootElement().getName(), all);
        }
        return all;
    }

    private void collectXPaths(org.dom4j.Element el, String path, Set<String> out) {
        out.add(path);
        for (Object c : el.elements()) {
            org.dom4j.Element child = (org.dom4j.Element) c;
            collectXPaths(child, path + "/" + child.getName(), out);
        }
    }

    private Set<String> extractXPaths(String xml) throws Exception {
        Document doc = new STAXEventReader().readDocument(
                FACTORY.createXMLEventReader(new java.io.StringReader(xml)));
        Set<String> out = new LinkedHashSet<>();
        collectXPaths(doc.getRootElement(), "/" + doc.getRootElement().getName(), out);
        return out;
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
git add src/test/resources/infer/tier-a/typical-invoice/ \
        src/test/java/org/brylex/xmlgen/infer/TemplateInferrerTest.java
git commit -m "Add Tier A acceptance: typical-invoice fixture and property tests"
```

---

### Task 22: Tier A — homogeneous, nested-repeat, optional-elements fixtures

**Files:**
- Create: `src/test/resources/infer/tier-a/{homogeneous,nested-repeat,optional-elements}/sample-*.xml`
- Modify: `src/test/java/org/brylex/xmlgen/infer/TemplateInferrerTest.java`

- [ ] **Step 1: Write 10 homogeneous samples** (alle felter ≤ 5 distinkte verdier)

- [ ] **Step 2: Write 6 nested-repeat samples** (outer + inner cardinality variasjon)

- [ ] **Step 3: Write 6 optional-elements samples** (subset-kjede mønster)

- [ ] **Step 4: Add three @Test methods, en per fixture, hver med (a)+(b)+(c)-asserts**

- [ ] **Step 5: Run + commit**

```bash
git commit -m "Add Tier A acceptance: homogeneous, nested-repeat, optional-elements fixtures"
```

---

### Task 23: Tier B golden-file infrastructure + første fixture

**Files:**
- Create: `src/test/resources/infer/tier-b/typical-invoice/samples/sample-*.xml`
- Create: `src/test/resources/infer/tier-b/typical-invoice/expected.xml`
- Modify: `src/test/java/org/brylex/xmlgen/infer/TemplateInferrerTest.java`
- Modify: `pom.xml` (add `regenerate-goldens` profile)

- [ ] **Step 1: Copy 6 samples fra Tier A typical-invoice til Tier B**

- [ ] **Step 2: Generate initial expected.xml by running inferrer**

Run a one-off in a scratch test:
```java
@Test
@org.junit.jupiter.api.Disabled("manual: regenerate golden")
void writeGolden() throws Exception {
    List<XMLEventReader> samples = loadSamples("infer/tier-b/typical-invoice/samples", 6);
    InferredTemplate t = TemplateInferrer.infer(samples);
    Files.writeString(Path.of("src/test/resources/infer/tier-b/typical-invoice/expected.xml"),
            t.templateXml());
}
```

Run it once, commit the result.

- [ ] **Step 3: Add golden-diff test**

```java
@Test
void typicalInvoiceMatchesGolden() throws Exception {
    List<XMLEventReader> samples = loadSamples("infer/tier-b/typical-invoice/samples", 6);
    InferredTemplate t = TemplateInferrer.infer(samples);
    String expected = readResource("infer/tier-b/typical-invoice/expected.xml");
    assertThat(t.templateXml()).isEqualTo(expected);
}
```

- [ ] **Step 4: Add Maven profile for regenerating goldens**

In `pom.xml`:

```xml
<profile>
    <id>regenerate-goldens</id>
    <properties>
        <regenerate.goldens>true</regenerate.goldens>
    </properties>
</profile>
```

In the golden test, branch on `System.getProperty("regenerate.goldens")` to either compare-or-overwrite.

- [ ] **Step 5: Run + commit**

```bash
git commit -m "Add Tier B golden-file infrastructure with typical-invoice fixture"
```

---

### Task 24: Tier B — nested-coherence, choose-distinct, uuid-detection fixtures

**Files:**
- Create: `src/test/resources/infer/tier-b/{nested-coherence,choose-distinct,uuid-detection}/{samples/,expected.xml}`
- Modify: `src/test/java/org/brylex/xmlgen/infer/TemplateInferrerTest.java`

- [ ] **Step 1: Write samples + generate goldens for each fixture (steg som Task 23)**

- [ ] **Step 2: Add tre @Test-metoder for golden-diff**

- [ ] **Step 3: Run + commit**

```bash
git commit -m "Add Tier B fixtures: nested-coherence, choose-distinct, uuid-detection"
```

---

### Task 25: Determinism og anonymiserings-eksempel-test

**Files:**
- Create: `src/test/java/org/brylex/xmlgen/infer/DeterminismTest.java`
- Create: `src/test/java/org/brylex/xmlgen/infer/AnonymizationExampleTest.java`

- [ ] **Step 1: DeterminismTest**

```java
// DeterminismTest.java
package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DeterminismTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void shuffledInputProducesIdenticalOutput() throws Exception {
        List<String> resourceNames = new ArrayList<>();
        for (int i = 1; i <= 8; i++) resourceNames.add("infer/tier-a/typical-invoice/sample-" + i + ".xml");

        InferredTemplate forward = TemplateInferrer.infer(load(resourceNames));
        List<String> shuffled = new ArrayList<>(resourceNames);
        Collections.shuffle(shuffled, new Random(123));
        InferredTemplate backward = TemplateInferrer.infer(load(shuffled));

        assertThat(forward.templateXml()).isEqualTo(backward.templateXml());
        // Pool row order also identical
        for (String poolName : forward.pools().poolNames()) {
            assertThat(forward.pools().get(poolName).rows())
                    .isEqualTo(backward.pools().get(poolName).rows());
        }
    }

    private List<XMLEventReader> load(List<String> names) throws Exception {
        List<XMLEventReader> readers = new ArrayList<>();
        for (String n : names) {
            InputStream is = getClass().getClassLoader().getResourceAsStream(n);
            readers.add(FACTORY.createXMLEventReader(is));
        }
        return readers;
    }
}
```

Note: requires `Pools.poolNames()` accessor — add to `Pools.java` if missing.

- [ ] **Step 2: AnonymizationExampleTest**

```java
// AnonymizationExampleTest.java
package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pool;
import org.brylex.xmlgen.Pools;
import org.dom4j.Document;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymizationExampleTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void anonymizedExpansionContainsNoLiteralSampleValues() throws Exception {
        List<XMLEventReader> samples = loadSamples();
        Set<String> originalValues = collectLeafValues(samples);

        InferredTemplate t = TemplateInferrer.infer(loadSamples());

        // Anonymizer: replace every pool value with a synthetic gibberish string
        Pools anonymized = anonymize(t.pools(), this::syntheticReplacement);

        InferredTemplate anonTemplate = new InferredTemplate(
                t.templateXml(), anonymized, t.warnings());
        Document expanded = new STAXEventReader().readDocument(anonTemplate.expand(new Random(0)));
        Set<String> expandedValues = leafTextsOf(expanded);

        Set<String> leaked = new LinkedHashSet<>(originalValues);
        leaked.retainAll(expandedValues);
        // Allow empty/whitespace and numeric values (random-int doesn't anonymize automatically)
        leaked.removeIf(v -> v.matches("[\\s\\d.-]+"));
        assertThat(leaked).isEmpty();
    }

    private Pools anonymize(Pools src, Function<String, String> fn) {
        Pools.Builder b = Pools.builder();
        for (String poolName : src.poolNames()) {
            List<Map<String, String>> newRows = new ArrayList<>();
            for (Map<String, String> row : src.get(poolName).rows()) {
                Map<String, String> mapped = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : row.entrySet()) {
                    mapped.put(e.getKey(), fn.apply(e.getValue()));
                }
                newRows.add(mapped);
            }
            b.inline(poolName, newRows);
        }
        return b.build();
    }

    private String syntheticReplacement(String original) {
        return "anon-" + Integer.toHexString(original.hashCode());
    }

    // helpers loadSamples, collectLeafValues, leafTextsOf omitted for brevity but inlined in actual test
    private List<XMLEventReader> loadSamples() throws Exception { /* ... */ }
    private Set<String> collectLeafValues(List<XMLEventReader> readers) { /* ... */ return Set.of(); }
    private Set<String> leafTextsOf(Document doc) { /* ... */ return Set.of(); }
}
```

- [ ] **Step 3: Run + commit**

```bash
git commit -m "Add determinism and anonymization-example acceptance tests"
```

---

## Phase 8 — Documentation

### Task 26: README og CLAUDE.md-oppdatering

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add "Template Inference" section to README**

After the "Branching" section, add:

```markdown
## Template Inference

Don't have a template? Point the inferrer at 5–50 real sample documents
and it produces an annotated template plus `Pools`:

```java
List<XMLEventReader> samples = Files.list(Path.of("samples"))
        .map(p -> {
            try (InputStream is = Files.newInputStream(p)) {
                return XMLInputFactory.newFactory().createXMLEventReader(is);
            } catch (Exception e) { throw new RuntimeException(e); }
        })
        .toList();

InferredTemplate inferred = TemplateInferrer.infer(samples);
XMLEventReader reader = inferred.expand(new Random(42));
```

Heuristics: repeating siblings become `gen:repeat="min..max"`, numeric/date
fields become `gen:random-*`, low-cardinality coherent leaves become
`gen:pick` against a generated pool, alternating subtrees become `gen:choose`,
UUID-format fields become `gen:random-uuid`.

For anonymization, transform the returned `Pools` before expansion. See
`AnonymizationExampleTest` for a canonical pattern.

Tune behavior via `InferenceConfig`:

```java
InferenceConfig strict = new InferenceConfig(/* ... */, /* strictMode */ true);
TemplateInferrer.infer(samples, strict);
```

Strict mode promotes all silent degradations to `InferenceException`.
```

- [ ] **Step 2: Update CLAUDE.md**

In the Architecture section, append after the decorator chain diagram:

```markdown
## Inference pipeline

`org.brylex.xmlgen.infer` is a separate pipeline from the expansion chain:

```
TemplateInferrer.infer(samples)
  └── ShapeBuilder              // XMLEventReaders → ShapeNode-tre
  └── AnalyzerPipeline          // RepeatAnalyzer → IncrementAnalyzer →
                                //   RandomRangeAnalyzer → ChooseAnalyzer →
                                //   PickCoherenceAnalyzer
  └── TemplateRenderer          // → XML-streng + Pools
  └── InferredTemplate (xml, pools, warnings)
```

The output template is valid input to `GeneratingXMLEventReader`. Inferred
templates close the loop: real samples → template → unlimited synthetic
expansion.
```

- [ ] **Step 3: Run full test suite as final sanity check**

Run: `mvn verify`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "Document template inference pipeline"
```

---

## Self-review (post-skriving)

### Spec coverage check

| Spec-seksjon | Task som dekker |
|---|---|
| §Motivasjon | (kontekst, ikke direkte implementert) |
| §Scope spor 1 (lift gen:repeat-i-choose) | Task 3 |
| §Scope spor 2 (gen:repeat min..max) | Task 1 |
| §Scope spor 3 (gen:random-uuid) | Task 2 |
| §Scope spor 4 (inferrer-pipelinen) | Task 5–20 |
| §Arkitektur (subpakke + komponent-grenser) | Task 5–8 (typer) + Task 9–20 (komponenter) |
| §ShapeNode | Task 6 |
| §Directive sealed interface | Task 6 |
| §ShapeBuilder | Task 9, 10, 11 |
| §RepeatAnalyzer | Task 13 |
| §IncrementAnalyzer | Task 15 |
| §RandomRangeAnalyzer (inkl. UUID) | Task 14 |
| §ChooseAnalyzer | Task 16 |
| §PickCoherenceAnalyzer | Task 17 |
| §TemplateRenderer | Task 18, 19 |
| §InferredTemplate | Task 7 |
| §InferenceConfig | Task 5 |
| §Feilhåndtering (fail-loud) | Spredt: Task 5 (Exception), 11 (samples disagree, gen-ns), 8 (empty samples) |
| §Feilhåndtering (best-effort warnings) | Task 5 (InferenceWarning), spredt over Task 14, 16, 17 |
| §Cross-sample-rekkefølge (kanonisk frequency-mode) | Task 10 |
| §Mixed content handling | Task 11, 19 |
| §Pool naming + lex-sortering | Task 17 (naming), Task 18 (sort) |
| §Acceptance Tier A (5–6 fixture-sett) | Task 21, 22 |
| §Acceptance Tier B (3–4 golden) | Task 23, 24 |
| §Determinism test | Task 25 |
| §AnonymizationExampleTest | Task 25 |
| §README + CLAUDE.md | Task 4 (delvis), Task 26 (full) |

### Placeholder scan

Plan har én bevisst "skissert"-seksjon i Task 15 (IncrementAnalyzer) som peker
tilbake på Task 13-mønsteret + krever en utvidelse på `AnalysisContext` som
også brukes av Task 16 og 17. Det er en koblet endring som best implementeres
sammen — implementer Task 15-step 2 i samme commit som Task 16/17 hvis det er
mer naturlig.

Task 16 (`ChooseAnalyzer`) og Task 17 (`PickCoherenceAnalyzer`) har "details
sketched in spec" — disse er bevisst kortere fordi spec-en allerede har
algoritmen detaljert. Engineer skal lese spec for algoritmiske detaljer.

Ingen `TBD` eller "implement later" i selve task-stegene.

### Type-konsistens

`ShapeNode`, `Directive`, `Range`, `ContentItem`, `InferenceWarning` —
signaturer matcher på tvers av tasks. `Pool` / `Pools` — bruker eksisterende
xmlgen-typer (sjekk at `Pools.poolNames()` finnes, ellers legg til i Task 25).

`TemplateRenderer.Result` er en intern record i Task 18; brukes konsistent.

`AnalysisContext` har metodene `warn`, `config`, `warnings`, `signaturesAt`,
`iterationValuesAt`, `recordIteration` — disse må alle implementeres, ikke
bare den minimale Task 12-versjonen. Task 15–17 utvider gradvis. Implementer
i sammen-commit hvis mulig.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-20-xmlgen-infer.md`. Two execution options:

1. **Subagent-Driven (recommended)** — Jeg dispatcher en fresh subagent per task, review mellom tasks, rask iterasjon
2. **Inline Execution** — Kjør tasks i denne sesjonen via executing-plans, batch-utførelse med checkpoints

Hvilken tilnærming?
