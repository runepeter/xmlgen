package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pools;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    @Test
    void rendersEmptyRoot() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        TemplateRenderer.Result r = new TemplateRenderer().render(root);
        assertThat(r.xml()).contains("<order");
        assertThat(r.xml()).contains("xmlns:gen=\"urn:xml:gen\"");
    }

    @Test
    void rendersRepeatRangeDirective() {
        ShapeNode root = withChild("line");
        ShapeNode line = childOf(root, "line");
        line.setDirective(new Directive.Repeat(2, 5));
        String xml = new TemplateRenderer().render(root).xml();
        assertThat(xml).contains("gen:repeat=\"2..5\"");
    }

    @Test
    void rendersFixedRepeatWithoutRange() {
        ShapeNode root = withChild("line");
        ShapeNode line = childOf(root, "line");
        line.setDirective(new Directive.Repeat(3, 3));
        String xml = new TemplateRenderer().render(root).xml();
        assertThat(xml).contains("gen:repeat=\"3\"");
        assertThat(xml).doesNotContain("3..3");
    }

    @Test
    void rendersRandomIntDirective() {
        ShapeNode root = withChild("qty");
        ShapeNode qty = childOf(root, "qty");
        qty.setDirective(new Directive.Random(new Range.IntRange(1, 10)));
        String xml = new TemplateRenderer().render(root).xml();
        assertThat(xml).contains("gen:random-int=\"1..10\"");
    }

    @Test
    void rendersRandomUuidDirective() {
        ShapeNode root = withChild("id");
        ShapeNode id = childOf(root, "id");
        id.setDirective(new Directive.Random(new Range.Uuid()));
        String xml = new TemplateRenderer().render(root).xml();
        assertThat(xml).contains("gen:random-uuid=\"true\"");
    }

    @Test
    void pickAddsPoolToPools() {
        ShapeNode root = withChild("sku");
        ShapeNode sku = childOf(root, "sku");
        sku.valueSamples().add("A");
        sku.valueSamples().add("B");
        sku.valueSamples().add("C");
        sku.setDirective(new Directive.Pick("line", "sku"));
        TemplateRenderer.Result r = new TemplateRenderer().render(root);
        assertThat(r.xml()).contains("gen:pick=\"line/sku\"");
        assertThat(r.pools().get("line").rows()).hasSize(3);
    }

    @Test
    void leafWithoutDirectiveEmitsFirstObservedValue() {
        ShapeNode root = withChild("status");
        ShapeNode status = childOf(root, "status");
        status.valueSamples().add("active");
        String xml = new TemplateRenderer().render(root).xml();
        assertThat(xml).contains("<status>active</status>");
    }

    @Test
    void attributesAreRenderedWithFirstObservedValue() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        root.attributes().computeIfAbsent(new QName("id"), k -> new ShapeNode.ValueSamples()).add("A");
        root.attributes().get(new QName("id")).add("B");
        String xml = new TemplateRenderer().render(root).xml();
        assertThat(xml).contains("id=\"A\"");
        assertThat(xml).doesNotContain("id=\"B\"");
    }

    // --- helpers ---

    private ShapeNode withChild(String name) {
        ShapeNode root = new ShapeNode(new QName("root"), "/root");
        root.orderedContent().add(new ContentItem.ChildSlot(
                new ShapeNode(new QName(name), "/root/" + name)));
        return root;
    }

    private ShapeNode childOf(ShapeNode parent, String name) {
        return parent.orderedContent().stream()
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> ((ContentItem.ChildSlot) ci).node())
                .filter(n -> n.qName().getLocalPart().equals(name))
                .findFirst().orElseThrow();
    }
}
