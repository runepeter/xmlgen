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
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ChooseTest {

    @Test
    public void testSingleBranchAlwaysWins() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <gen:choose>"
                + "    <gen:when><only>1</only></gen:when>"
                + "  </gen:choose>"
                + "</xml>";

        Document document = parse(xml, fixedRandom());
        assertThat(count(document, "//only")).isEqualTo(1);
        assertThat(value(document, "//only")).isEqualTo("1");
    }

    @Test
    public void testChooseStripsItselfFromOutput() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <gen:choose>"
                + "    <gen:when><x>A</x></gen:when>"
                + "  </gen:choose>"
                + "</xml>";

        Document document = parse(xml, fixedRandom());
        assertThat(count(document, "//*[namespace-uri()='urn:xml:gen']")).isEqualTo(0);
    }

    @Test
    public void testZeroBranchesEmitsNothing() throws Exception {

        // <gen:choose> with no <gen:when> children still parses and just
        // produces no content from itself.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <before>B</before>"
                + "  <gen:choose></gen:choose>"
                + "  <after>A</after>"
                + "</xml>";

        Document document = parse(xml, fixedRandom());
        assertThat(value(document, "//before")).isEqualTo("B");
        assertThat(value(document, "//after")).isEqualTo("A");
    }

    @Test
    public void testWeightingFavorsHighWeight() throws Exception {

        // Run 1000 single-iteration documents. The 90-weight branch
        // should win ~90% of the time. We allow generous bounds since
        // the test uses a real (seeded) Random.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <gen:choose>"
                + "    <gen:when weight=\"90\"><kind>heavy</kind></gen:when>"
                + "    <gen:when weight=\"10\"><kind>light</kind></gen:when>"
                + "  </gen:choose>"
                + "</xml>";

        Random rng = new Random(42L);
        int heavy = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            Document document = parse(xml, rng);
            if ("heavy".equals(value(document, "//kind"))) {
                heavy++;
            }
        }
        assertThat(heavy).isBetween((int) (total * 0.85), (int) (total * 0.95));
    }

    @Test
    public void testChooseInsideRepeatPicksIndependentlyPerIteration() throws Exception {

        // With weights 1:1 and 100 iterations, we expect a mix — not
        // all-heavy or all-light. This proves each iteration makes its
        // own choice rather than reusing the first iteration's pick.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <row gen:repeat=\"100\">"
                + "    <gen:choose>"
                + "      <gen:when><k>A</k></gen:when>"
                + "      <gen:when><k>B</k></gen:when>"
                + "    </gen:choose>"
                + "  </row>"
                + "</xml>";

        Document document = parse(xml, new Random(7L));
        int a = count(document, "//k[text()='A']");
        int b = count(document, "//k[text()='B']");
        assertThat(a + b).isEqualTo(100);
        assertThat(a).isBetween(30, 70);
        assertThat(b).isBetween(30, 70);
    }

    @Test
    public void testChosenBranchCombinesWithPick() throws Exception {

        Pools pools = Pools.builder()
                .inline("customers", List.of(
                        Map.of("name", "Acme"),
                        Map.of("name", "Beta")))
                .inline("suppliers", List.of(
                        Map.of("name", "Foo"),
                        Map.of("name", "Bar")))
                .build();

        // Force the choose to always pick the second branch by giving
        // the first branch weight 0.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <row gen:repeat=\"2\">"
                + "    <gen:choose>"
                + "      <gen:when weight=\"0\">"
                + "        <party><name gen:pick=\"customers/name\">_</name></party>"
                + "      </gen:when>"
                + "      <gen:when weight=\"1\">"
                + "        <party><name gen:pick=\"suppliers/name\">_</name></party>"
                + "      </gen:when>"
                + "    </gen:choose>"
                + "  </row>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, pools, fixedRandom());
        Document document = new STAXEventReader().readDocument(reader);

        List<Element> rows = document.getRootElement().elements("row");
        // Both rows should pick from suppliers (weight=1 branch wins).
        assertThat(rows.get(0).element("party").elementText("name")).isEqualTo("Foo");
        assertThat(rows.get(1).element("party").elementText("name")).isEqualTo("Bar");
    }

    @Test
    public void testNonIntegerWeightThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <gen:choose>"
                + "    <gen:when weight=\"bogus\"><x>1</x></gen:when>"
                + "  </gen:choose>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight");
    }

    @Test
    public void testNegativeWeightThrows() throws Exception {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xml xmlns:gen=\"urn:xml:gen\">"
                + "  <gen:choose>"
                + "    <gen:when weight=\"-3\"><x>1</x></gen:when>"
                + "  </gen:choose>"
                + "</xml>";

        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main);

        assertThatThrownBy(() -> new STAXEventReader().readDocument(reader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    private Document parse(String xml, Random random) throws Exception {
        XMLEventReader main = XMLInputFactory.newFactory().createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(main, Pools.empty(), random);
        return new STAXEventReader().readDocument(reader);
    }

    private static Random fixedRandom() {
        return new Random(42L);
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
