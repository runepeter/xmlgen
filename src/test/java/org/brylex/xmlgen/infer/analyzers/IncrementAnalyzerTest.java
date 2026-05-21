package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementAnalyzerTest {

    @Test
    void detectsStep1Increment() {
        ShapeNode root = treeWithRepeat();
        Map<String, List<List<String>>> seqs = Map.of(
                "/order/line/seq", List.of(List.of("1", "2", "3"))
        );
        AnalysisContext ctx = ctxWithIterationValues(seqs);

        new IncrementAnalyzer().analyze(root, ctx);

        ShapeNode seq = leafAt(root, "/order/line/seq");
        assertThat(seq.directive()).contains(new Directive.Increment(1, 1));
    }

    @Test
    void detectsStep5IncrementAcrossSamples() {
        ShapeNode root = treeWithRepeat();
        Map<String, List<List<String>>> seqs = Map.of(
                "/order/line/seq", List.of(
                        List.of("5", "10", "15"),
                        List.of("20", "25", "30")
                )
        );
        AnalysisContext ctx = ctxWithIterationValues(seqs);

        new IncrementAnalyzer().analyze(root, ctx);

        ShapeNode seq = leafAt(root, "/order/line/seq");
        assertThat(seq.directive()).contains(new Directive.Increment(5, 5));
    }

    @Test
    void inconsistentStepIsNoOp() {
        ShapeNode root = treeWithRepeat();
        Map<String, List<List<String>>> seqs = Map.of(
                "/order/line/seq", List.of(
                        List.of("1", "2", "3"),
                        List.of("10", "20", "30") // step 10 != 1
                )
        );
        AnalysisContext ctx = ctxWithIterationValues(seqs);

        new IncrementAnalyzer().analyze(root, ctx);

        ShapeNode seq = leafAt(root, "/order/line/seq");
        assertThat(seq.directive()).isEmpty();
    }

    @Test
    void singleIterationIsInsufficient() {
        ShapeNode root = treeWithRepeat();
        Map<String, List<List<String>>> seqs = Map.of(
                "/order/line/seq", List.of(List.of("42"))  // only one observation, can't detect step
        );
        AnalysisContext ctx = ctxWithIterationValues(seqs);

        new IncrementAnalyzer().analyze(root, ctx);

        ShapeNode seq = leafAt(root, "/order/line/seq");
        assertThat(seq.directive()).isEmpty();
    }

    @Test
    void nonNumericValuesAreSkipped() {
        ShapeNode root = treeWithRepeat();
        Map<String, List<List<String>>> seqs = Map.of(
                "/order/line/seq", List.of(List.of("a", "b", "c"))
        );
        AnalysisContext ctx = ctxWithIterationValues(seqs);

        new IncrementAnalyzer().analyze(root, ctx);

        ShapeNode seq = leafAt(root, "/order/line/seq");
        assertThat(seq.directive()).isEmpty();
    }

    @Test
    void leafWithoutRepeatParentIsSkipped() {
        // Tree: /order/seq with NO Directive.Repeat on parent
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode seq = new ShapeNode(new QName("seq"), "/order/seq");
        root.orderedContent().add(new ContentItem.ChildSlot(seq));

        Map<String, List<List<String>>> seqs = Map.of(
                "/order/seq", List.of(List.of("1", "2", "3"))
        );
        AnalysisContext ctx = ctxWithIterationValues(seqs);

        new IncrementAnalyzer().analyze(root, ctx);

        assertThat(seq.directive()).isEmpty();
    }

    private ShapeNode treeWithRepeat() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        ShapeNode line = new ShapeNode(new QName("line"), "/order/line");
        ShapeNode seq = new ShapeNode(new QName("seq"), "/order/line/seq");
        root.orderedContent().add(new ContentItem.ChildSlot(line));
        line.orderedContent().add(new ContentItem.ChildSlot(seq));
        line.setDirective(new Directive.Repeat(2, 3));
        return root;
    }

    private ShapeNode leafAt(ShapeNode root, String xpath) {
        if (root.xpath().equals(xpath)) return root;
        for (ContentItem ci : root.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                ShapeNode found = leafAt(cs.node(), xpath);
                if (found != null) return found;
            }
        }
        return null;
    }

    private AnalysisContext ctxWithIterationValues(Map<String, List<List<String>>> seqs) {
        return new AnalysisContext(InferenceConfig.defaults(), List.of(), seqs);
    }
}
