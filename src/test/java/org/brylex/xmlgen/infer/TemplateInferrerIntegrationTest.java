package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateInferrerIntegrationTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void endToEndProducesValidTemplate() throws Exception {
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

        // Template parses and expands without exceptions
        XMLEventReader expanded = t.expand(new Random(42));
        while (expanded.hasNext()) {
            expanded.next();
        }

        // Template contains expected directives
        assertThat(t.templateXml()).contains("gen:repeat=\"2..3\"");
        assertThat(t.templateXml()).contains("gen:random-int=\"1..7\"");
    }

    @Test
    void integerSequenceProducesRepeatAndRandomInt() throws Exception {
        InferredTemplate t = TemplateInferrer.infer(List.of(
                parse("<order><line><qty>5</qty></line><line><qty>10</qty></line></order>"),
                parse("<order><line><qty>1</qty></line><line><qty>15</qty></line><line><qty>8</qty></line></order>"),
                parse("<order><line><qty>20</qty></line><line><qty>3</qty></line></order>")
        ));
        assertThat(t.templateXml()).contains("gen:repeat=");
        assertThat(t.templateXml()).contains("gen:random-int=\"1..20\"");
    }

    @Test
    void coherentSamplesProducePickDirective() throws Exception {
        InferredTemplate t = TemplateInferrer.infer(coherentSamples());

        // No false Choose at /order
        assertThat(t.templateXml()).doesNotContain("gen:choose");
        assertThat(t.templateXml()).doesNotContain("gen:when");

        // gen:pick on name and iban
        assertThat(t.templateXml()).containsPattern("<name[^>]*gen:pick");
        assertThat(t.templateXml()).containsPattern("<iban[^>]*gen:pick");

        // Pool exists with 5 distinct rows
        assertThat(t.pools().poolNames()).isNotEmpty();
        String poolName = t.pools().poolNames().iterator().next();
        assertThat(t.pools().get(poolName).rows()).hasSize(5);

        // No spurious MixedTypeFallback for name/iban
        assertThat(t.warnings())
                .noneMatch(w -> w instanceof InferenceWarning.MixedTypeFallback m
                        && (m.xpath().endsWith("/name") || m.xpath().endsWith("/iban")));

        // Template parses and expands without errors
        XMLEventReader expanded = t.expand(new Random(42));
        while (expanded.hasNext()) expanded.next();
    }

    private List<XMLEventReader> coherentSamples() throws Exception {
        String[] xs = {
            "<order><line><party><name>Acme</name><iban>NO11</iban></party><qty>1</qty></line></order>",
            "<order><line><party><name>Beta</name><iban>NO22</iban></party><qty>2</qty></line><line><party><name>Acme</name><iban>NO11</iban></party><qty>5</qty></line></order>",
            "<order><line><party><name>Gamma</name><iban>NO33</iban></party><qty>3</qty></line></order>",
            "<order><line><party><name>Delta</name><iban>NO44</iban></party><qty>4</qty></line></order>",
            "<order><line><party><name>Epsilon</name><iban>NO55</iban></party><qty>7</qty></line></order>"
        };
        List<XMLEventReader> rs = new ArrayList<>();
        for (String s : xs) rs.add(FACTORY.createXMLEventReader(new StringReader(s)));
        return rs;
    }

    private XMLEventReader parse(String xml) throws Exception {
        return FACTORY.createXMLEventReader(new StringReader(xml));
    }
}
