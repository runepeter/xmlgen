package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
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

    private XMLEventReader parse(String xml) throws Exception {
        return FACTORY.createXMLEventReader(new StringReader(xml));
    }
}
