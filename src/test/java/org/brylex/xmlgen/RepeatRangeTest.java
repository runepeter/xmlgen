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
