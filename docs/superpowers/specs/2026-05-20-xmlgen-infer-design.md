# xmlgen infer — design

Status: **brainstorming-output, smidd gjennom 15 grills, klar for multi-agent review**
Dato: 2026-05-20
Eier: Rune Peter Bjørnstad

## Motivasjon

I dag krever xmlgen at en utvikler håndskriver en annotert template før den kan
produsere test-data. Det er friksjon — verktøyet er smart, men onboarding-kurven
gjør det nisje. Tilskuddet inverterer workflow-en: pek inferrer-en på 5–50 ekte
XML-eksempler, og den emitterer en annotert template + tilhørende `Pools`-objekt
som ekspanderer til strukturelt-lignende output.

To brukstilfeller:
- **Synthesis (onboarding):** Reduser tiden fra "har samples" til "har en
  template" fra timer til sekunder.
- **Anonymisering av produksjons-data:** Brukerens egen post-prosessering på
  `Pools`-objektet (eksempel i repo'et, ikke kjerne-feature) erstatter ekte
  verdier med syntetiske før ekspansjon.

## Scope — fire arbeids-spor i én PR

1. **Lift `gen:repeat`-i-`gen:choose`-begrensning** i ekspansjons-laget. Uten
   dette kan inferrer emittere ugyldige templates når samples har repeterende
   elementer inne i alternerende strukturer.
2. **`gen:repeat="min..max"`-syntax** i kjernen. Tillater fast `gen:repeat="N"`
   *eller* range. Range-form trekker et random heltall i intervallet per
   ekspansjon. Bruker `RandomXMLEventReader`s eksisterende `Random`-instans.
3. **`gen:random-uuid="true"`-direktiv** i kjernen. Genererer en ny UUID per
   ekspansjon. Detekteres av inferrer for felter med UUID-format.
4. **`TemplateInferrer.infer(samples)`-pipelinen** — selve hovedverket.

Alle fire spor leveres i samme PR (begrunnelse: inferrer-en er meningsløs uten
de andre tre, og oppdelt PR-sekvens har scope-creep-risiko mellom landinger).

## Arkitektur

Inferrer-en lever i en ny subpakke `org.brylex.xmlgen.infer`. Den er en
separat pipeline fra ekspansjons-laget; de deler bare direktiv-vokabular og
output-format (`Pools` + annotert XML).

```
caller
  └── TemplateInferrer.infer(samples, [config])
        └── ShapeBuilder              // XMLEventReaders → ShapeNode-tre
        └── AnalyzerPipeline          // dekorerer noder med direktiv-beslutninger
              ├── RepeatAnalyzer
              ├── IncrementAnalyzer
              ├── RandomRangeAnalyzer     // inkl. UUID-format-deteksjon
              ├── ChooseAnalyzer
              └── PickCoherenceAnalyzer  // sist; respekterer first-writer-wins
        └── TemplateRenderer          // dekorert tre → XML-streng + Pools
        └── return InferredTemplate(xml, pools)
```

Pakke-grense: `infer`-subpakken kan importere fra hovedpakken; ikke motsatt.
Hovedpakken vet ikke at inferrer-en finnes.

## Komponenter

### `ShapeNode`

Immutable etter bygging (kun `directive`-feltet muteres av analyzers).

| Felt | Type | Beskrivelse |
|---|---|---|
| `qName` | `QName` | Element-navn (inkl. namespace) |
| `xpath` | `String` | Stabil identifikator (eks. `/order/line`) |
| `orderedContent` | `List<ContentItem>` | Ordnet sekvens av barn, tekst, CDATA, PI, kommentarer |
| `valueSamples` | `Multiset<String>` | Tekst-innhold observert per element-instans |
| `cardinalityPerParent` | `IntSummaryStatistics` | Søsken-kardinalitet per parent-instans |
| `attributes` | `Map<QName, Multiset<String>>` | Attributtnavn → observerte verdier |
| `hasMixedContent` | `boolean` | Mixed content observert i minst én sample |
| `directive` | `Optional<Directive>` | Tom inntil en Analyzer setter den |

`ContentItem` er en sealed interface med `ChildSlot(ShapeNode)`, `Text(String)`,
`Cdata(String)`, `ProcessingInstruction(...)`, `Comment(String)`.

**Søsken-kollaps-regel:** Konsekutive søsken med samme qName kollapser til én
ChildSlot med kardinalitet > 1. Ikke-konsekutive søsken med samme qName blir
separate ChildSlot-er.

**Cross-sample-rekkefølge:** Første samples rekkefølge vinner ved uenighet
(ingen varsel i v1).

### `Directive`

Sealed interface, én record per direktiv-type:

- `Repeat(int min, int max)` — `min == max` rendrer som `gen:repeat="N"`, ellers
  som `gen:repeat="min..max"`
- `Increment(int step, int startingValue)`
- `Random(Range range)` — Range har subtyper `IntRange`, `AmountRange`,
  `DateRange`, `Uuid`
- `Pick(String poolName, String column)`
- `Choose(List<Branch> branches)` — Branch holder vekt + subtre

Max én directive per node. Analyzer-rekkefølgen sikrer first-writer-wins.

### `ShapeBuilder`

Bygger ShapeNode-treet fra `List<XMLEventReader>` via direkte StAX-pull (ingen
`_XMLEvent` eller andre decoratorer). To pass: (1) per sample, walk og merge
inn i levende ShapeNode; (2) post-process for å regne ut statistikk.

**Bevaring:**
- Default namespace bevares på root
- `xsi:*` og andre attributter passeres uendret
- Mixed content, CDATA, PI, kommentarer passeres uendret per element

**Fail loud:**
- Tom samples-liste
- Samples har ulike root-elementer
- En sample har `xmlns:gen="..."` med en annen URI enn `urn:xml:gen`
- Sample-fil mislykkes i StAX-parsing (wrappet med 1-basert indeks)

### Analyzers

Hver Analyzer implementerer `void analyze(ShapeNode root)` og dekorerer
relevante noder. Rekkefølgen er fastlåst i `AnalyzerPipeline`-konstruktøren.

#### `RepeatAnalyzer`
- Trigger: en ChildSlot har `cardinalityPerParent.max > 1` på tvers av samples
- Output: `Directive.Repeat(min, max)` der min/max er observerte ekstremer
- Hvis `min == max` → renderes som fast `gen:repeat="N"`
- Hvis `min == 0 < max` → mulig optional element; lar ChooseAnalyzer håndtere

#### `IncrementAnalyzer`
- Trigger: ShapeNode under en repeat-marked parent har monotont økende
  heltallsverdier per parent-instans, med samme step på tvers av observasjoner
- Output: `Directive.Increment(step, startingValue=min_observed)`
- Krever ≥ 2 observasjoner per parent-instans i minst én sample

#### `RandomRangeAnalyzer`
- Trigger 1: alle observerte verdier parser som heltall, kardinalitet >
  `randomCardinalityThreshold` (default 0.5) av observasjoner →
  `Random(IntRange(min, max))`
- Trigger 2: samme for desimaltall → `Random(AmountRange(min, max, scale))`
  hvor scale = max-observert-desimaler
- Trigger 3: samme for ISO-dato → `Random(DateRange(min, max))`
- Trigger 4: alle observerte verdier matcher
  `^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` →
  `Random(Uuid)`

#### `ChooseAnalyzer`
- Per parent-ShapeNode: lag barn-signaturer = sortert sett av
  `{qName for direct child}`. Grupper parent-instanser per signatur.
- Én signatur → no-op
- Signaturer danner *subset-kjede* (A ⊂ B ⊂ ...) → de ekstra elementene er
  valgfrie. Wrap hvert valgfritt element i `gen:choose` med to brancher
  (med element + tom branch), vekter = observert ratio
- Signaturer er gjensidig distinkte → ekte `gen:choose` med én `<gen:when>` per
  signatur-gruppe, vekter = gruppens observasjons-count
- Threshold: signaturer < 10% av observasjoner droppes som støy
- Hard cap: max 4 brancher per choose; over det → fall tilbake til
  majoritets-signatur

#### `PickCoherenceAnalyzer` (kjøres sist)
- Per parent-ShapeNode: identifiser leaf-children som ikke allerede har
  direktiv og som har lav kardinalitet (≤ 20% av observasjoner *og* < 50
  distinkte verdier)
- Grupper leaves som co-okkurrerer **bijektivt** innen samme parent-instans på
  tvers av samples (bijektiv: hver verdi-tuppel er unik, ingen verdi gjentas
  mot ulike par-verdier)
- **K-threshold:** Bijektiv pool emitteres kun hvis observerte distinkte
  rader ≥ `minPoolRows` (default 5). Under threshold: fall til individuelle
  picks per felt, eller — hvis kardinalitet = 1 — first-observed-value som
  literal placeholder.
- Pool-navn = path fra nærmeste `gen:repeat`-anker (eller root) ned til parent
  som inneholder gruppen, dot-separerte lokal-navn. Eks:
  `/order/line/party/{name,iban}` → pool = `line.party`
- Pool-rad-rekkefølge: leksikografisk på første kolonne-verdi (deterministisk
  ekspansjon uavhengig av input-sample-rekkefølge)

### `TemplateRenderer`

Tar dekorert ShapeNode-tre + `PoolBuilder`. Emitterer XML-streng + bygger
`Pools`. Bruker `StringBuilder` med manuell escaping (ikke dom4j).

**Pretty-printing per node:**
- Strukturerte elementer (kun barn-elementer, `hasMixedContent=false`):
  re-formatteres med konsekvent innrykk (2-space)
- Mixed-content-elementer: rendres verbatim fra første sample (innhold,
  whitespace, CDATA bevares uendret)
- Direktiver vi injecter går alltid på strukturerte elementer i vanlig bruk

**WARNING-kommentar** emitteres like over `gen:pick` på poolen når alle
observerte verdier var unike (`distinctValues == observations`):
```xml
<!-- WARNING: every observed value was unique; pool will cycle on expansion. -->
<id gen:pick="line/id">_</id>
```

### `InferredTemplate`

```java
public record InferredTemplate(String templateXml, Pools pools) {
    public XMLEventReader expand(Random random) { ... }
    public XMLEventReader expand() { return expand(new Random()); }
}
```

### `InferenceConfig`

Tunable value object med dokumenterte defaults. Brukere kan overstyre
individuelle thresholds.

```java
public record InferenceConfig(
    double lowCardinalityRatio,        // default 0.20
    int lowCardinalityMaxDistinct,     // default 50
    double randomCardinalityThreshold, // default 0.50
    int minPoolRows,                   // default 5
    double chooseNoiseThreshold,       // default 0.10
    int chooseMaxBranches              // default 4
) {
    public static InferenceConfig defaults() { ... }
}
```

API:
```java
TemplateInferrer.infer(List<XMLEventReader> samples);
TemplateInferrer.infer(List<XMLEventReader> samples, InferenceConfig config);
```

## Feilhåndtering

**Fail loud (`InferenceException` — `RuntimeException`-subklasse):**

| Situasjon | Melding |
|---|---|
| `samples` tom | "at least one sample required" |
| Sample mislykkes i StAX-parsing | "sample N failed to parse" (cause attached) |
| Ulike root-elementer | "samples disagree on root element: A vs B" |
| `xmlns:gen` med annen URI | "sample N declares xmlns:gen with conflicting URI: ..." |
| Namespace-kollisjon (samme lokal-navn, ulike namespaces) ved pool-naming | "namespace collision at <XPath>: <ns1> vs <ns2>" |
| Mixed-content-element trenger gen:repeat | "gen:repeat on mixed-content element not supported in v1" |

**Best-effort (stille):**

| Situasjon | Håndtering |
|---|---|
| Felt numerisk i én sample, tekst i en annen | RandomRangeAnalyzer hopper over; faller til PickCoherence |
| Delvis bijektiv koherens (< 100%) | Ikke felles pool; individuelle picks |
| Choose-signatur < 10% representasjon | Droppet som støy |
| Attributter med variabilitet | First-observed-value (kjent v1-begrensning) |

**Ingen logging-rammeverk-dependency** i v1.

## Datastrøm (eksempel)

Samples:
```xml
<!-- sample 1 -->                          <!-- sample 2 -->
<order id="A">                             <order id="B">
  <line><sku>X1</sku><qty>2</qty></line>     <line><sku>X1</sku><qty>5</qty></line>
  <line><sku>X2</sku><qty>3</qty></line>     <line><sku>X3</sku><qty>7</qty></line>
                                             <line><sku>X1</sku><qty>1</qty></line>
</order>                                   </order>
```

ShapeNode-tre (sammendrag):
- `/order` — attribute `id` med 2 distinkte verdier (A, B)
- `/order/line` — cardinalityPerParent = [2, 3]
- `/order/line/sku` — valueSamples = [X1, X2, X1, X3, X1] (3 distinkte: X1, X2, X3)
- `/order/line/qty` — valueSamples = [2, 3, 5, 7, 1] (5 distinkte, alle numeriske)

Analyzer-output:
- RepeatAnalyzer: `/order/line` → `Repeat(min=2, max=3)` → `gen:repeat="2..3"`
- RandomRangeAnalyzer: `/order/line/qty` → `Random(IntRange(1, 7))` → `gen:random-int="1..7"`
- PickCoherenceAnalyzer: `/order/line/sku` — kardinalitet 3 < K=5 → ingen pool;
  bare first-observed-value → `<sku>X1</sku>`

Output template:
```xml
<order xmlns:gen="urn:xml:gen" id="A">
  <line gen:repeat="2..3">
    <sku>X1</sku>
    <qty gen:random-int="1..7">_</qty>
  </line>
</order>
```

Pools: tom (ingen pick i dette eksempelet, da K-threshold ikke ble nådd).

## Testing

### Lag 1 — Analyzer-enhetstester
Én testklasse per analyzer (`RepeatAnalyzerTest`, etc.). Tester mot håndlagde
ShapeNode-trær via en `ShapeNodes`-test-helper.

### Lag 2 — ShapeBuilder- og Renderer-tester
`ShapeBuilderTest`: mater XML-strenger, asserter struktur (XPath, kardinalitet,
samples) på ut-treet. `TemplateRendererTest`: håndlagde dekorerte ShapeNode-trær
→ verifiser at output-XML parser, inneholder forventede direktiver, og at
Pools-objektet har forventede rader.

### Lag 3 — End-to-end fixture-suite (acceptance gate)

**Tier A — Property-fixtures (5–6 sett, threshold-trygge):**
Asserter kun tre round-trip-properties:
- (a) `extractXPaths(template) ⊇ unionOfXPaths(samples)` — strukturell dekning
- (b) `directiveAt(xpath)` matcher kanoniske forventninger der relevant
- (c) Ekspander template N=100 ganger med `new Random(42)`; alle observerte
  verdier i (b)-merkede felter ligger innenfor sample-observert range / pool

**Tier B — Direktiv-fixtures (3–4 sett, golden files):**
Hver fixture har `samples/`-katalog + `expected.xml`-fil. Test diff'er
inferrert output mot golden. `mvn -Pregenerate-goldens`-profil oppdaterer.
Tier B-fikstur omfatter:
- Typisk faktura (gen:repeat + gen:pick + gen:random)
- Nested gen:repeat + cross-level koherens
- Optional element + ekte choose
- UUID-format detection + WARNING-kommentar

**Homogen-fixture:**
Eget Tier A-fixture-sett hvor alle felter har ≤ 5 distinkte verdier.
Tvinger oss til å forholde oss til "alt blir pick"-edge case.

### Lag 4 — Anonymiserings-eksempel som test
`AnonymizationExampleTest` viser kanonisk bruk: infer → anvend brukerlevert
`Function<Pools, Pools>` → ekspander → verifiser at output ikke inneholder
literal-verdier fra samples. Både test og dokumentasjon.

### Determinisme-test
`infer(samples)` og `infer(shuffled(samples))` skal gi identisk
templateXml *og* identisk Pools (samme rader i samme rekkefølge etter lex-sort).

## Kjerne-utvidelser

### 1. `gen:repeat="min..max"`-syntax
`RecordingXMLEventReader` parser allerede `gen:repeat`-attributtet. Utvid
parser: hvis verdi inneholder `..`, parse som range; ellers som heltall.
Range-form trekker `random.nextInt(max - min + 1) + min` per ekspansjon.
Bruker eksisterende `Random` fra `GeneratingXMLEventReader`. Eksisterende
`gen:repeat="N"` blir `Repeat(N, N)` i datamodell.

### 2. Lift `gen:repeat`-i-`gen:choose`-begrensning
Per CLAUDE.md: chosen branch's events bypass main directive loop. Endring:
når `handleChoose()` queue'er events i `pending`-deque-en, må de fortsatt
gå gjennom `delegate.nextEvent()`-løkken (eller equivalents) slik at
`RecordingXMLEventReader` får dem. Krever endring i
`GeneratingXMLEventReader.nextEvent()` for å la pending-events trigge
recorder-pathene. Eksisterende test som dokumenterer begrensningen
oppdateres til å demonstrere ny støttet bruk.

### 3. `gen:random-uuid="true"`-direktiv
Ny håndtering i `RandomXMLEventReader`: hvis StartElement har
`gen:random-uuid` attributtet med verdi `"true"`, erstatt elementets tekst med
`UUID.randomUUID().toString()`. Bruker ikke `Random` (UUID-API'et bruker
SecureRandom internt); dokumenter at UUID ikke er seedet for reproduserbarhet.

## Kjente begrensninger (dokumentert, ikke fixet i v1)

- **Attributter med variabilitet:** Inferrer emitterer alltid første observerte
  verdi. Ingen attributt-direktiver i v1.
- **gen:repeat på mixed-content-elementer:** Fail loud.
- **Verdier som er 100% unike men ikke UUID:** Pool emitteres med WARNING-
  kommentar.
- **Stor enkelt-sample memory:** v1 leser hele sample-tre i minne. Egnet for
  5–50 samples × ~MB-størrelse. Ikke for én sample × GB.
- **Scripting / custom value generators:** Out of scope; foreslås spec'es
  separat etter v1 leveres.

## Acceptance-kriterier

V1 er ferdig når:

1. Alle Lag 1–4 tester passerer
2. Alle Tier A property-asserts holder for 5–6 fixture-sett
3. Alle Tier B golden files matcher (eller er bevisst oppdatert i samme PR)
4. `mvn verify` passerer ren
5. Anonymiserings-eksempel demonstrerer at brukerens egen Pools-transform
   produserer literal-free output
6. Determinisme-test passerer: shuffle av input gir identisk output
7. README oppdatert med inferens-seksjon og minimum-eksempel
8. CLAUDE.md oppdatert med arkitektur-pekere til `infer`-subpakken
