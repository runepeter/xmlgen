package org.brylex.xmlgen;

import org.dom4j.Document;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Document firstRun = expand(template, 42);
        Document secondRun = expand(template, 42);
        assertThat(firstRun.asXML()).isEqualTo(secondRun.asXML());
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

    @Test
    void minEqualsMaxResolvesToExactCount() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="3..3"><sku>X</sku></line>
                </order>
                """;
        Document doc = expand(template, 0);
        assertThat(doc.selectNodes("/order/line")).hasSize(3);
    }

    @Test
    void whitespaceAroundRangeBoundsIsTolerated() throws Exception {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="2 .. 5"><sku>X</sku></line>
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
    void minGreaterThanMaxThrowsIllegalArgumentException() {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="5..2"><sku>X</sku></line>
                </order>
                """;
        assertThatThrownBy(() -> expand(template, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gen:repeat")
                .hasMessageContaining("min must be <= max")
                .hasMessageContaining("5..2");
    }

    @Test
    void missingMinBoundThrowsIllegalArgumentException() {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="..5"><sku>X</sku></line>
                </order>
                """;
        assertThatThrownBy(() -> expand(template, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gen:repeat value must be 'min..max'")
                .hasMessageContaining("..5");
    }

    @Test
    void missingMaxBoundThrowsIllegalArgumentException() {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="2.."><sku>X</sku></line>
                </order>
                """;
        assertThatThrownBy(() -> expand(template, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gen:repeat value must be 'min..max'")
                .hasMessageContaining("2..");
    }

    @Test
    void nonNumericBoundsThrowIllegalArgumentException() {
        String template = """
                <order xmlns:gen="urn:xml:gen">
                    <line gen:repeat="a..b"><sku>X</sku></line>
                </order>
                """;
        assertThatThrownBy(() -> expand(template, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gen:repeat value must be 'min..max'")
                .hasMessageContaining("a..b");
    }

    private Document expand(String template, long seed) throws Exception {
        XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(template));
        XMLEventReader reader = new GeneratingXMLEventReader(source, Pools.empty(), new Random(seed));
        return new STAXEventReader().readDocument(reader);
    }
}
