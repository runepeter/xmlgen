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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        UUID.fromString(id);
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

    @Test
    void invalidUuidValueThrows() {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <id gen:random-uuid="yes">_</id>
                </order>
                """;
        assertThatThrownBy(() -> expand(template, 0))
                .satisfies(t -> {
                    Throwable relevant = t.getCause() != null ? t.getCause() : t;
                    assertThat(relevant).isInstanceOf(IllegalArgumentException.class);
                    assertThat(relevant.getMessage()).contains("gen:random-uuid");
                });
    }

    private Document expand(String template, long seed) throws Exception {
        XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(template));
        XMLEventReader reader = new GeneratingXMLEventReader(source, Pools.empty(), new Random(seed));
        return new STAXEventReader().readDocument(reader);
    }
}
