package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatAnalyzerTest {

    @Test
    void variableCardinalityProducesRangeDirective() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.cardinalityPerParent().accept(2);
        line.cardinalityPerParent().accept(5);
        line.cardinalityPerParent().accept(3);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(line.directive()).contains(new Directive.Repeat(2, 5));
    }

    @Test
    void fixedCardinalityProducesFixedRepeat() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.cardinalityPerParent().accept(3);
        line.cardinalityPerParent().accept(3);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(line.directive()).contains(new Directive.Repeat(3, 3));
    }

    @Test
    void singletonCardinalityIsNoOp() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.cardinalityPerParent().accept(1);
        line.cardinalityPerParent().accept(1);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(line.directive()).isEmpty();
    }

    @Test
    void deepNestedNodesAreVisited() {
        ShapeNode root = new ShapeNode(new QName("a"), "/a");
        ShapeNode b = new ShapeNode(new QName("b"), "/a/b");
        ShapeNode c = new ShapeNode(new QName("c"), "/a/b/c");
        root.orderedContent().add(new ContentItem.ChildSlot(b));
        b.orderedContent().add(new ContentItem.ChildSlot(c));
        c.cardinalityPerParent().accept(2);
        c.cardinalityPerParent().accept(4);

        new RepeatAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));

        assertThat(c.directive()).contains(new Directive.Repeat(2, 4));
        assertThat(b.directive()).isEmpty(); // b has no cardinality recorded
    }
}
