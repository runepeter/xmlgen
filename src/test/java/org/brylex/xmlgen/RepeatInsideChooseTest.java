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
        assertThat(items).hasSize(4); // 2 sections × 2 items
    }

    private Document expand(String template, long seed) throws Exception {
        XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(template));
        XMLEventReader reader = new GeneratingXMLEventReader(source, Pools.empty(), new Random(seed));
        return new STAXEventReader().readDocument(reader);
    }
}
