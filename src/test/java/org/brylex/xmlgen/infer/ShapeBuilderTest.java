package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShapeBuilderTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();
    // Woodstox preserves CDATA sections as distinct events (isCData() == true)
    private static final XMLInputFactory CDATA_FACTORY;

    static {
        // Explicitly use Woodstox to ensure CDATA sections are NOT coalesced into
        // regular character events — the JDK's built-in factory ignores IS_COALESCING=false
        // for the isCData() flag.
        CDATA_FACTORY = new com.ctc.wstx.stax.WstxInputFactory();
        CDATA_FACTORY.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
    }

    @Test
    void buildsTreeFromSingleSample() throws Exception {
        ShapeNode root = build("""
                <order>
                    <line><sku>X1</sku><qty>5</qty></line>
                    <line><sku>X2</sku><qty>3</qty></line>
                </order>
                """);

        assertThat(root.qName().getLocalPart()).isEqualTo("order");
        assertThat(root.xpath()).isEqualTo("/order");

        ShapeNode line = childOf(root, "line");
        assertThat(line.cardinalityPerParent().getMax()).isEqualTo(2);

        ShapeNode sku = childOf(line, "sku");
        assertThat(sku.valueSamples().counts()).containsKeys("X1", "X2");

        ShapeNode qty = childOf(line, "qty");
        assertThat(qty.valueSamples().counts()).containsKeys("5", "3");
    }

    @Test
    void mergesMultipleSamples() throws Exception {
        ShapeNode root = build(
                "<order><line><sku>X1</sku></line></order>",
                "<order><line><sku>X2</sku></line><line><sku>X3</sku></line></order>"
        );
        ShapeNode line = childOf(root, "line");
        ShapeNode sku = childOf(line, "sku");
        assertThat(sku.valueSamples().counts()).containsKeys("X1", "X2", "X3");
        assertThat(line.cardinalityPerParent().getMin()).isEqualTo(1);
        assertThat(line.cardinalityPerParent().getMax()).isEqualTo(2);
    }

    @Test
    void capturesAttributesAsMultiset() throws Exception {
        ShapeNode root = build(
                "<order id=\"A\"><line/></order>",
                "<order id=\"B\"><line/></order>",
                "<order id=\"A\"><line/></order>"
        );
        ShapeNode.ValueSamples idAttr = root.attributes().values().iterator().next();
        assertThat(idAttr.total()).isEqualTo(3);
        assertThat(idAttr.distinctCount()).isEqualTo(2);
        assertThat(idAttr.counts()).containsEntry("A", 2).containsEntry("B", 1);
    }

    @Test
    void emptySamplesListThrows() {
        ShapeBuilder builder = new ShapeBuilder(InferenceConfig.defaults());
        assertThatThrownBy(builder::build)
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("at least one sample");
    }

    @Test
    void disagreeingRootElementsThrows() {
        assertThatThrownBy(() -> build(
                "<order><line/></order>",
                "<invoice><line/></invoice>"
        )).isInstanceOf(InferenceException.class)
          .hasMessageContaining("disagree on root");
    }

    @Test
    void genNamespaceInSampleThrows() {
        assertThatThrownBy(() -> build(
                "<order xmlns:gen=\"urn:xml:gen\"><line gen:repeat=\"3\"/></order>"
        )).isInstanceOf(InferenceException.class)
          .hasMessageContaining("gen:");
    }

    @Test
    void maxDepthExceededThrows() {
        InferenceConfig shallow = new InferenceConfig(
                0.20, 50, 0.50, 5, 0.10, 4, 1000,
                3,    // maxSampleDepth = 3
                false, false);
        ShapeBuilder builder = new ShapeBuilder(shallow);
        assertThatThrownBy(() -> {
            XMLEventReader r = FACTORY.createXMLEventReader(new StringReader(
                    "<a><b><c><d><e/></d></c></b></a>"));
            builder.absorb(r);
            builder.build();
        }).isInstanceOf(InferenceException.class)
          .hasMessageContaining("maxSampleDepth");
    }

    @Test
    void canonicalOrderUsesFrequencyMode() throws Exception {
        // 3 samples: 2 with order (a,b), 1 with order (b,a) → canonical is (a,b)
        ShapeNode root = build(
                "<root><a/><b/></root>",
                "<root><a/><b/></root>",
                "<root><b/><a/></root>"
        );
        List<String> order = childOrder(root);
        assertThat(order).containsExactly("a", "b");
    }

    @Test
    void canonicalOrderUsesLexTiebreakOnEqualFrequency() throws Exception {
        // 1 sample with (a,b), 1 with (b,a) — tie; lex: (a,b) < (b,a)
        ShapeNode root = build(
                "<root><a/><b/></root>",
                "<root><b/><a/></root>"
        );
        List<String> order = childOrder(root);
        assertThat(order).containsExactly("a", "b");
    }

    @Test
    void inputOrderIndependence() throws Exception {
        ShapeNode forward = build(
                "<root><a/><b/></root>",
                "<root><b/><a/></root>"
        );
        ShapeNode reverse = build(
                "<root><b/><a/></root>",
                "<root><a/><b/></root>"
        );
        assertThat(childOrder(forward)).isEqualTo(childOrder(reverse));
    }

    private List<String> childOrder(ShapeNode root) {
        return root.orderedContent().stream()
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> ((ContentItem.ChildSlot) ci).node().qName().getLocalPart())
                .toList();
    }

    private ShapeNode build(String... samples) throws Exception {
        ShapeBuilder builder = new ShapeBuilder(InferenceConfig.defaults());
        for (String s : samples) {
            XMLEventReader r = FACTORY.createXMLEventReader(new StringReader(s));
            builder.absorb(r);
        }
        return builder.build();
    }

    @Test
    void mixedContentIsMarked() throws Exception {
        ShapeNode root = build("<root><p>hello <b>world</b>!</p></root>");
        ShapeNode p = childOf(root, "p");
        assertThat(p.hasMixedContent()).isTrue();
    }

    @Test
    void pureStructuredElementIsNotMixed() throws Exception {
        ShapeNode root = build("<root><line><sku>X</sku></line></root>");
        ShapeNode line = childOf(root, "line");
        assertThat(line.hasMixedContent()).isFalse();
        ShapeNode sku = childOf(line, "sku");
        assertThat(sku.hasMixedContent()).isFalse();
    }

    @Test
    void defaultNamespaceIsPreserved() throws Exception {
        ShapeNode root = build("<order xmlns=\"http://schemas.com/v1\"><line/></order>");
        assertThat(root.qName().getNamespaceURI()).isEqualTo("http://schemas.com/v1");
    }

    @Test
    void genPrefixWithDifferentUriThrows() {
        assertThatThrownBy(() -> build(
                "<order xmlns:gen=\"http://other-namespace.com/\"><line gen:foo=\"bar\"/></order>"
        )).isInstanceOf(InferenceException.class)
          .hasMessageContaining("gen");
    }

    @Test
    void cdataIsCapturedAsContentItem() throws Exception {
        // Must use a non-coalescing factory so the CDATA section is preserved as a distinct event
        ShapeBuilder builder = new ShapeBuilder(InferenceConfig.defaults());
        XMLEventReader r = CDATA_FACTORY.createXMLEventReader(
                new StringReader("<root><note><![CDATA[<raw>data</raw>]]></note></root>"));
        builder.absorb(r);
        ShapeNode root = builder.build();
        ShapeNode note = childOf(root, "note");
        boolean hasCdata = note.orderedContent().stream().anyMatch(ci -> ci instanceof ContentItem.Cdata);
        assertThat(hasCdata).isTrue();
    }

    @Test
    void mixedContentDivergenceThrows() {
        // Sample 1: mixed (text + child). Sample 2: only structured (no significant text).
        assertThatThrownBy(() -> build(
                "<root><p>before<b>middle</b>after</p></root>",
                "<root><p><b>middle</b></p></root>"
        )).isInstanceOf(InferenceException.class)
          .hasMessageContaining("mixed-content");
    }

    private ShapeNode childOf(ShapeNode parent, String localName) {
        return parent.orderedContent().stream()
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> ((ContentItem.ChildSlot) ci).node())
                .filter(n -> n.qName().getLocalPart().equals(localName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no child '" + localName + "' in " + parent.xpath()));
    }
}
