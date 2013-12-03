package org.brylex.xmlgen;

import com.google.common.base.Stopwatch;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.STAXEventReader;
import org.dom4j.io.XMLWriter;
import org.junit.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class GeneratingXMLEventReaderTest {

    @Test
    public void testNoGeneration() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(count(document, "//xml")).isEqualTo(1);
    }

    @Test
    public void testSingleRepeatElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\" gen:repeat=\"1\">"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(count(document, "//xml")).isEqualTo(1);
    }

    @Test
    public void testRepeatElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <child gen:repeat=\"2\"></child>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(count(document, "//child")).isEqualTo(2);
    }

    @Test
    public void testSiblingRepeatElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <child gen:repeat=\"2\"></child>"
                + "  <friend gen:repeat=\"3\"></friend>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(count(document, "//child")).isEqualTo(2);
        assertThat(count(document, "//friend")).isEqualTo(3);
    }

    @Test
    public void testNestedRepeatElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <child gen:repeat=\"2\">"
                + "    <friend gen:repeat=\"3\"></friend>"
                + "  </child>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(count(document, "//child")).isEqualTo(2);
        assertThat(count(document, "//friend")).isEqualTo(6);
    }

    @Test
    public void testTripleNestedRepeatElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <child gen:repeat=\"2\">"
                + "    <friend gen:repeat=\"3\">"
                + "      <enemy gen:repeat=\"4\"></enemy>"
                + "    </friend>"
                + "  </child>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(count(document, "//child")).isEqualTo(2);
        assertThat(count(document, "//friend")).isEqualTo(6);
        assertThat(count(document, "//enemy")).isEqualTo(24);
    }

    @Test
    public void testIncrementByOneElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\" gen:increment=\"1\">0</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);

        assertThat(document).isNotNull();
        assertThat(value(document, "//xml")).isEqualTo("1");
    }

    @Test
    public void testIncrementByTwoElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\" gen:increment=\"2\">0</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        Document document = new STAXEventReader().readDocument(reader);
        assertThat(document).isNotNull();
        assertThat(value(document, "//xml")).isEqualTo("2");
    }

    @Test
    public void testNestedIncrementElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <jalla gen:increment=\"1\">3</jalla>"
                + "  <child>"
                + "    <friend gen:increment=\"10\">1</friend>"
                + "  </child>"
                + "</xml>";

        try (Reader xmlReader = new StringReader(xml)) {

            XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(xmlReader);
            XMLEventReader reader = new GeneratingXMLEventReader(main);

            Document document = new STAXEventReader().readDocument(reader);
            assertThat(document).isNotNull();
            assertThat(value(document, "//jalla")).isEqualTo("4");
            assertThat(value(document, "//friend")).isEqualTo("11");
        }
    }

    @Test
    public void testIncrementInsideRepeatElement() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <child gen:repeat=\"2\">"
                + "    <friend gen:increment=\"2\">0</friend>"
                + "  </child>"
                + "</xml>";

        try (Reader xmlReader = new StringReader(xml)) {

            XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(xmlReader);
            XMLEventReader reader = new GeneratingXMLEventReader(main);

            Document document = new STAXEventReader().readDocument(reader);
            assertThat(document).isNotNull();
            assertThat(count(document, "//child")).isEqualTo(2);
            assertThat(value(document, "//child[1]/friend")).isEqualTo("2");
            assertThat(value(document, "//child[2]/friend")).isEqualTo("4");
        }
    }

    private int count(Node node, String xpath) {
        return ((Double) node.createXPath("count(" + xpath + ")").evaluate(node)).intValue();
    }

    private String value(Node node, String xpath) {
        Object evaluated = node.createXPath(xpath).evaluate(node);
        if (evaluated instanceof List) {

            List list = (List) evaluated;
            if (list.size() != 1) {
                throw new IllegalStateException("XPath didn't yield exactly one result.");
            }

            return "" + ((Element) list.get(0)).getText();

        } else {
            return "" + ((Element) evaluated).getText();
        }
    }

    private void print(final Document document) {

        final StringWriter sw = new StringWriter();

        try {
            XMLWriter writer = new XMLWriter(sw, OutputFormat.createPrettyPrint());
            writer.write(document);
        } catch (Exception e) {
            throw new RuntimeException("Error pretty printing xml:\n" + document.asXML(), e);
        }

        System.err.println(sw.toString());
    }

}
