package org.brylex.xmlgen;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PoolPickXMLEventReaderTest {

    private static Pools customers() {
        return Pools.builder()
                .inline("customers", List.of(
                        Map.of("name", "Acme AS", "iban", "NO11"),
                        Map.of("name", "Beta AS", "iban", "NO22"),
                        Map.of("name", "Gamma AS", "iban", "NO33")))
                .build();
    }

    @Test
    public void testSinglePickSubstitutesValue() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <name gen:pick=\"customers/name\">PLACEHOLDER</name>"
                + "</xml>";

        Document document = parse(xml, customers());
        assertThat(value(document, "//name")).isEqualTo("Acme AS");
    }

    @Test
    public void testPickStripsGenAttributeFromOutput() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <name gen:pick=\"customers/name\">x</name>"
                + "</xml>";

        Document document = parse(xml, customers());
        assertThat(count(document, "//name[@*[namespace-uri()='urn:xml:gen']]")).isEqualTo(0);
    }

    @Test
    public void testMultiplePicksFromSamePoolInSameIterationShareRow() throws Exception {

        // Two picks against the same pool in the same gen:repeat iteration
        // must read from the SAME row, so the rendered name+iban are coherent.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <party gen:repeat=\"3\">"
                + "    <name gen:pick=\"customers/name\">_</name>"
                + "    <iban gen:pick=\"customers/iban\">_</iban>"
                + "  </party>"
                + "</xml>";

        Document document = parse(xml, customers());
        List<Element> parties = document.getRootElement().elements("party");
        assertThat(parties).hasSize(3);
        assertThat(parties.get(0).elementText("name")).isEqualTo("Acme AS");
        assertThat(parties.get(0).elementText("iban")).isEqualTo("NO11");
        assertThat(parties.get(1).elementText("name")).isEqualTo("Beta AS");
        assertThat(parties.get(1).elementText("iban")).isEqualTo("NO22");
        assertThat(parties.get(2).elementText("name")).isEqualTo("Gamma AS");
        assertThat(parties.get(2).elementText("iban")).isEqualTo("NO33");
    }

    @Test
    public void testPickCyclesWhenRepeatExceedsPoolSize() throws Exception {

        // The pool has 3 rows; repeating 5 times should wrap.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <party gen:repeat=\"5\">"
                + "    <name gen:pick=\"customers/name\">_</name>"
                + "  </party>"
                + "</xml>";

        Document document = parse(xml, customers());
        List<Element> parties = document.getRootElement().elements("party");
        assertThat(parties).hasSize(5);
        assertThat(parties.get(0).elementText("name")).isEqualTo("Acme AS");
        assertThat(parties.get(1).elementText("name")).isEqualTo("Beta AS");
        assertThat(parties.get(2).elementText("name")).isEqualTo("Gamma AS");
        assertThat(parties.get(3).elementText("name")).isEqualTo("Acme AS");
        assertThat(parties.get(4).elementText("name")).isEqualTo("Beta AS");
    }

    @Test
    public void testIndependentPoolsAdvanceIndependently() throws Exception {

        Pools twoPools = Pools.builder()
                .inline("customers", List.of(
                        Map.of("name", "Acme"),
                        Map.of("name", "Beta")))
                .inline("suppliers", List.of(
                        Map.of("name", "Foo"),
                        Map.of("name", "Bar")))
                .build();

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <row gen:repeat=\"2\">"
                + "    <customer gen:pick=\"customers/name\">_</customer>"
                + "    <supplier gen:pick=\"suppliers/name\">_</supplier>"
                + "  </row>"
                + "</xml>";

        Document document = parse(xml, twoPools);
        List<Element> rows = document.getRootElement().elements("row");
        assertThat(rows.get(0).elementText("customer")).isEqualTo("Acme");
        assertThat(rows.get(0).elementText("supplier")).isEqualTo("Foo");
        assertThat(rows.get(1).elementText("customer")).isEqualTo("Beta");
        assertThat(rows.get(1).elementText("supplier")).isEqualTo("Bar");
    }

    @Test
    public void testPickCombinesWithIncrement() throws Exception {

        // gen:pick on one element and gen:increment on another within the
        // same iteration both work, advancing independently.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <row gen:repeat=\"3\">"
                + "    <name gen:pick=\"customers/name\">_</name>"
                + "    <seq gen:increment=\"1\">100</seq>"
                + "  </row>"
                + "</xml>";

        Document document = parse(xml, customers());
        List<Element> rows = document.getRootElement().elements("row");
        // pick: rows cycle through the pool, one per iteration.
        // increment: accumulates from previous iteration's result (101 -> 102 -> 103),
        // matching the established behavior pinned in testIncrementInsideRepeatElement.
        assertThat(rows.get(0).elementText("name")).isEqualTo("Acme AS");
        assertThat(rows.get(0).elementText("seq")).isEqualTo("101");
        assertThat(rows.get(1).elementText("name")).isEqualTo("Beta AS");
        assertThat(rows.get(1).elementText("seq")).isEqualTo("102");
        assertThat(rows.get(2).elementText("name")).isEqualTo("Gamma AS");
        assertThat(rows.get(2).elementText("seq")).isEqualTo("103");
    }

    @Test
    public void testMalformedPickSpecThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <name gen:pick=\"customers\">_</name>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, customers());

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .hasMessageContaining("gen:pick");
    }

    @Test
    public void testUnknownPoolThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <name gen:pick=\"missing/col\">_</name>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, customers());

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown pool");
    }

    @Test
    public void testUnknownColumnThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <name gen:pick=\"customers/no-such-col\">_</name>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, customers());

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no column 'no-such-col'");
    }

    @Test
    public void testCsvLoading() throws Exception {

        String csv = "name,iban\nFirst,NO01\nSecond,NO02\n";
        Pools pools = Pools.builder()
                .csv("customers", new StringReader(csv))
                .build();

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <party gen:repeat=\"2\">"
                + "    <n gen:pick=\"customers/name\">_</n>"
                + "  </party>"
                + "</xml>";

        Document document = parse(xml, pools);
        List<Element> parties = document.getRootElement().elements("party");
        assertThat(parties.get(0).elementText("n")).isEqualTo("First");
        assertThat(parties.get(1).elementText("n")).isEqualTo("Second");
    }

    @Test
    public void testOuterPinSurvivesInnerRepeatIterations() throws Exception {

        Pools pools = Pools.builder()
                .inline("customers", List.of(
                        Map.of("name", "Acme"),
                        Map.of("name", "Beta")))
                .inline("products", List.of(
                        Map.of("sku", "P1"),
                        Map.of("sku", "P2"),
                        Map.of("sku", "P3")))
                .build();

        // Two orders, each with three lines. The customer pin must hold
        // across the three inner iterations, but products must advance
        // per line.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <order gen:repeat=\"2\">"
                + "    <customer gen:pick=\"customers/name\">_</customer>"
                + "    <line gen:repeat=\"3\">"
                + "      <sku gen:pick=\"products/sku\">_</sku>"
                + "    </line>"
                + "  </order>"
                + "</xml>";

        Document document = parse(xml, pools);
        List<Element> orders = document.getRootElement().elements("order");
        assertThat(orders).hasSize(2);

        // Order 1: customer pinned at Acme, three lines with P1, P2, P3.
        assertThat(orders.get(0).elementText("customer")).isEqualTo("Acme");
        List<Element> lines1 = orders.get(0).elements("line");
        assertThat(lines1).hasSize(3);
        assertThat(lines1.get(0).elementText("sku")).isEqualTo("P1");
        assertThat(lines1.get(1).elementText("sku")).isEqualTo("P2");
        assertThat(lines1.get(2).elementText("sku")).isEqualTo("P3");

        // Order 2: customer advances to Beta. Products cycle (P1 again).
        assertThat(orders.get(1).elementText("customer")).isEqualTo("Beta");
        List<Element> lines2 = orders.get(1).elements("line");
        assertThat(lines2).hasSize(3);
        assertThat(lines2.get(0).elementText("sku")).isEqualTo("P1");
        assertThat(lines2.get(1).elementText("sku")).isEqualTo("P2");
        assertThat(lines2.get(2).elementText("sku")).isEqualTo("P3");
    }

    @Test
    public void testInnerPickReadsOuterPin() throws Exception {

        Pools pools = Pools.builder()
                .inline("customers", List.of(
                        Map.of("name", "Acme", "iban", "NO11"),
                        Map.of("name", "Beta", "iban", "NO22")))
                .build();

        // Outer pick pins the customer row; inner repeat references the
        // same pool and should see the OUTER row, not start fresh.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <order gen:repeat=\"2\">"
                + "    <name gen:pick=\"customers/name\">_</name>"
                + "    <line gen:repeat=\"2\">"
                + "      <iban gen:pick=\"customers/iban\">_</iban>"
                + "    </line>"
                + "  </order>"
                + "</xml>";

        Document document = parse(xml, pools);
        List<Element> orders = document.getRootElement().elements("order");

        assertThat(orders.get(0).elementText("name")).isEqualTo("Acme");
        for (Element line : orders.get(0).elements("line")) {
            assertThat(line.elementText("iban")).isEqualTo("NO11");
        }

        assertThat(orders.get(1).elementText("name")).isEqualTo("Beta");
        for (Element line : orders.get(1).elements("line")) {
            assertThat(line.elementText("iban")).isEqualTo("NO22");
        }
    }

    @Test
    public void testNoPoolsConstructorStillWorks() throws Exception {

        // Backwards compatibility: the original single-arg constructor
        // must still work without any Pools wiring.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <child gen:repeat=\"2\"></child>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);
        Document document = new STAXEventReader().readDocument(reader);
        assertThat(count(document, "//child")).isEqualTo(2);
    }

    private Document parse(String xml, Pools pools) throws Exception {
        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, pools);
        return new STAXEventReader().readDocument(reader);
    }

    private int count(Node node, String xpath) {
        return ((Double) node.createXPath("count(" + xpath + ")").evaluate(node)).intValue();
    }

    private String value(Node node, String xpath) {
        Object evaluated = node.createXPath(xpath).evaluate(node);
        if (evaluated instanceof List) {
            List<?> list = (List<?>) evaluated;
            if (list.size() != 1) {
                throw new IllegalStateException("XPath didn't yield exactly one result.");
            }
            return ((Element) list.get(0)).getText();
        }
        return ((Element) evaluated).getText();
    }
}
