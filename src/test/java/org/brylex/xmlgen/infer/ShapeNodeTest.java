package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShapeNodeTest {

    @Test
    void newNodeIsEmpty() {
        ShapeNode node = new ShapeNode(new QName("order"), "/order");
        assertThat(node.qName().getLocalPart()).isEqualTo("order");
        assertThat(node.xpath()).isEqualTo("/order");
        assertThat(node.orderedContent()).isEmpty();
        assertThat(node.valueSamples().isEmpty()).isTrue();
        assertThat(node.attributes()).isEmpty();
        assertThat(node.hasMixedContent()).isFalse();
        assertThat(node.directive()).isEmpty();
    }

    @Test
    void setDirectiveStoresValue() {
        ShapeNode node = new ShapeNode(new QName("line"), "/order/line");
        node.setDirective(new Directive.Repeat(2, 5));
        assertThat(node.directive()).contains(new Directive.Repeat(2, 5));
    }

    @Test
    void setDirectiveTwiceThrows() {
        ShapeNode node = new ShapeNode(new QName("line"), "/order/line");
        node.setDirective(new Directive.Repeat(2, 5));
        assertThatThrownBy(() -> node.setDirective(new Directive.Repeat(3, 3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valueSamplesAccumulatesCounts() {
        ShapeNode node = new ShapeNode(new QName("sku"), "/sku");
        node.valueSamples().add("A");
        node.valueSamples().add("B");
        node.valueSamples().add("A");
        assertThat(node.valueSamples().total()).isEqualTo(3);
        assertThat(node.valueSamples().distinctCount()).isEqualTo(2);
        assertThat(node.valueSamples().counts()).containsEntry("A", 2);
        assertThat(node.valueSamples().counts()).containsEntry("B", 1);
    }

    @Test
    void repeatIsFixedWhenMinEqualsMax() {
        Directive.Repeat fixed = new Directive.Repeat(3, 3);
        assertThat(fixed.isFixed()).isTrue();
        Directive.Repeat range = new Directive.Repeat(2, 5);
        assertThat(range.isFixed()).isFalse();
    }
}
