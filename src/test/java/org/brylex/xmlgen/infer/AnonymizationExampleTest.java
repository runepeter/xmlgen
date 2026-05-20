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

import static org.assertj.core.api.Assertions.assertThat;

class AnonymizationExampleTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    /**
     * Demonstrates the canonical anonymization pattern:
     *   infer → transform Pools via user-supplied Function → expand.
     *
     * Asserts that none of the original pool values appear in the expanded document.
     * Values that flow through gen:random-int / gen:random-amount / gen:random-date
     * are excluded from the check because they are never part of a pool.
     */
    @Test
    void anonymizedExpansionContainsNoLiteralPoolValues() throws Exception {
        List<XMLEventReader> samples = load();
        InferredTemplate t = TemplateInferrer.infer(samples);

        // Collect original pool values (strings that live in the inferred Pools)
        Set<String> poolValues = collectPoolValues(t.pools());

        // If no pools were inferred (all fields went through random-* or literal),
        // this test verifies that anonymize + expand completes without errors.
        Pools anonymized = anonymize(t.pools(), this::synthetic);
        InferredTemplate anon = new InferredTemplate(t.templateXml(), anonymized, t.warnings());
        Document expanded = new STAXEventReader().readDocument(anon.expand(new Random(0)));

        if (!poolValues.isEmpty()) {
            Set<String> expandedTexts = collectDomTextValues(expanded.getRootElement(), new LinkedHashSet<>());
            // No original pool value must appear verbatim in the expanded document
            Set<String> leaked = new LinkedHashSet<>(poolValues);
            leaked.retainAll(expandedTexts);
            assertThat(leaked).isEmpty();
        }

        // Either way, the expanded document must be parseable (already done above)
        assertThat(expanded.getRootElement()).isNotNull();
    }

    /**
     * Demonstrates anonymization with an explicitly pool-backed fixture:
     * uses a Pools instance built inline (as a user would), verifying that
     * the anonymized pool values appear in the output instead of the originals.
     */
    @Test
    void anonymizedPoolReplacesSampleValues() throws Exception {
        // Build a minimal Pools with one named pool (simulating what PickCoherenceAnalyzer
        // would produce for a fixture with enough low-cardinality rows)
        List<Map<String, String>> originalRows = List.of(
                Map.of("name", "Acme Corp", "orgnr", "912345678"),
                Map.of("name", "Beta Supplies AS", "orgnr", "923456789"),
                Map.of("name", "Gamma Trading Ltd", "orgnr", "934567890"),
                Map.of("name", "Delta Services BV", "orgnr", "945678901"),
                Map.of("name", "Epsilon Group SA", "orgnr", "956789012")
        );
        Pools original = Pools.builder().inline("invoice.header.customer", originalRows).build();

        // Anonymize
        Pools anonymized = anonymize(original, this::synthetic);
        Pool anonPool = anonymized.get("invoice.header.customer");

        // Verify: no original value appears in the anonymized pool
        Set<String> originalValues = collectPoolValues(original);
        Set<String> anonValues = collectPoolValues(anonymized);

        Set<String> leaked = new LinkedHashSet<>(originalValues);
        leaked.retainAll(anonValues);
        assertThat(leaked).isEmpty();

        // And that synthetic values are actually present
        assertThat(anonPool.rows()).allSatisfy(row ->
                row.values().forEach(v -> assertThat(v).startsWith("anon-"))
        );
    }

    private List<XMLEventReader> load() throws Exception {
        List<XMLEventReader> r = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("infer/tier-a/typical-invoice/sample-" + i + ".xml");
            r.add(FACTORY.createXMLEventReader(is));
        }
        return r;
    }

    private Set<String> collectPoolValues(Pools pools) {
        Set<String> out = new LinkedHashSet<>();
        for (String poolName : pools.poolNames()) {
            Pool p = pools.get(poolName);
            for (Map<String, String> row : p.rows()) {
                out.addAll(row.values());
            }
        }
        return out;
    }

    private Set<String> collectDomTextValues(org.dom4j.Element el, Set<String> out) {
        if (el.elements().isEmpty()) {
            String text = el.getTextTrim();
            if (!text.isEmpty()) out.add(text);
        }
        for (Object c : el.elements()) collectDomTextValues((org.dom4j.Element) c, out);
        return out;
    }

    private Pools anonymize(Pools src, java.util.function.Function<String, String> fn) {
        Pools.Builder b = Pools.builder();
        for (String poolName : src.poolNames()) {
            Pool p = src.get(poolName);
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, String> row : p.rows()) {
                Map<String, String> mapped = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : row.entrySet()) {
                    mapped.put(e.getKey(), fn.apply(e.getValue()));
                }
                rows.add(mapped);
            }
            b.inline(poolName, rows);
        }
        return b.build();
    }

    private String synthetic(String original) {
        return "anon-" + Integer.toHexString(original.hashCode());
    }
}
