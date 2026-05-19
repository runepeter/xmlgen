package org.brylex.xmlgen;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RandomXMLEventReaderTest {

    @Test
    public void testRandomIntInRange() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <n gen:repeat=\"50\"><v gen:random-int=\"100..200\">_</v></n>"
                + "</xml>";

        Document document = parse(xml, new Random(1L));
        List<Element> values = document.getRootElement().selectNodes("//v").stream()
                .map(Element.class::cast).toList();
        assertThat(values).hasSize(50);
        for (Element v : values) {
            int parsed = Integer.parseInt(v.getText());
            assertThat(parsed).isBetween(100, 200);
        }
    }

    @Test
    public void testRandomIntDeterministicWithSeed() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <n gen:repeat=\"10\"><v gen:random-int=\"0..1000000\">_</v></n>"
                + "</xml>";

        Document a = parse(xml, new Random(123L));
        Document b = parse(xml, new Random(123L));

        List<String> aVals = a.getRootElement().selectNodes("//v").stream()
                .map(n -> ((Element) n).getText()).toList();
        List<String> bVals = b.getRootElement().selectNodes("//v").stream()
                .map(n -> ((Element) n).getText()).toList();
        assertThat(aVals).isEqualTo(bVals);
    }

    @Test
    public void testRandomAmountScaleAndRange() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <n gen:repeat=\"20\"><a gen:random-amount=\"0.00..1000.00\">_</a></n>"
                + "</xml>";

        Document document = parse(xml, new Random(2L));
        for (Object node : document.getRootElement().selectNodes("//a")) {
            String text = ((Element) node).getText();
            assertThat(text).matches("\\d+\\.\\d{2}");
            double v = Double.parseDouble(text);
            assertThat(v).isBetween(0.0, 1000.0);
        }
    }

    @Test
    public void testRandomDateInRange() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <n gen:repeat=\"30\"><d gen:random-date=\"2024-01-01..2024-12-31\">_</d></n>"
                + "</xml>";

        Document document = parse(xml, new Random(3L));
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-12-31");
        for (Object node : document.getRootElement().selectNodes("//d")) {
            LocalDate d = LocalDate.parse(((Element) node).getText());
            assertThat(d).isBetween(start, end);
        }
    }

    @Test
    public void testRandomDirectiveStripsAttribute() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <v gen:random-int=\"1..10\">_</v>"
                + "</xml>";

        Document document = parse(xml, new Random(4L));
        assertThat(count(document, "//v[@*[namespace-uri()='urn:xml:gen']]")).isEqualTo(0);
    }

    @Test
    public void testMalformedRangeThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <v gen:random-int=\"100\">_</v>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, Pools.empty(), new Random());

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min..max");
    }

    @Test
    public void testInvertedRangeThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <v gen:random-int=\"50..10\">_</v>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, Pools.empty(), new Random());

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min must be <= max");
    }

    @Test
    public void testRandomInsideChooseAndRepeat() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <ntry gen:repeat=\"10\">"
                + "    <gen:choose>"
                + "      <gen:when><amt gen:random-amount=\"1.00..100.00\">_</amt></gen:when>"
                + "    </gen:choose>"
                + "  </ntry>"
                + "</xml>";

        Document document = parse(xml, new Random(5L));
        List<?> amts = document.getRootElement().selectNodes("//amt");
        assertThat(amts).hasSize(10);
        for (Object node : amts) {
            double v = Double.parseDouble(((Element) node).getText());
            assertThat(v).isBetween(1.0, 100.0);
        }
    }

    private Document parse(String xml, Random random) throws Exception {
        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, Pools.empty(), random);
        return new STAXEventReader().readDocument(reader);
    }

    private int count(Node node, String xpath) {
        return ((Double) node.createXPath("count(" + xpath + ")").evaluate(node)).intValue();
    }
}
