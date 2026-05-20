package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RandomRangeAnalyzerTest {

    @Test
    void detectsIntRange() {
        ShapeNode root = leaf("qty", "1", "5", "3", "7", "2");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode qty = childOf(root, "qty");
        assertThat(qty.directive()).contains(
                new Directive.Random(new Range.IntRange(1, 7)));
    }

    @Test
    void detectsAmountRange() {
        ShapeNode root = leaf("amount", "1.00", "1.50", "2.25", "0.75", "3.00");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode amount = childOf(root, "amount");
        assertThat(amount.directive()).contains(new Directive.Random(
                new Range.AmountRange(new BigDecimal("0.75"), new BigDecimal("3.00"), 2)));
    }

    @Test
    void detectsDateRange() {
        ShapeNode root = leaf("when", "2024-01-01", "2024-06-15", "2024-12-31", "2024-03-10", "2024-09-30");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode when = childOf(root, "when");
        assertThat(when.directive()).contains(new Directive.Random(
                new Range.DateRange(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"))));
    }

    @Test
    void detectsUuidFormat() {
        ShapeNode root = leaf("id",
                "550e8400-e29b-41d4-a716-446655440000",
                "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "7a8b9c0d-1e2f-43a4-b5c6-d7e8f9a0b1c2",
                "0123abcd-4567-4abc-89ab-cdef01234567");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode id = childOf(root, "id");
        assertThat(id.directive()).contains(new Directive.Random(new Range.Uuid()));
    }

    @Test
    void mixedTypesEmitsWarning() {
        ShapeNode root = leaf("mix", "1", "two", "3", "four", "5");
        AnalysisContext ctx = new AnalysisContext(InferenceConfig.defaults());
        new RandomRangeAnalyzer().analyze(root, ctx);
        ShapeNode mix = childOf(root, "mix");
        assertThat(mix.directive()).isEmpty();
        assertThat(ctx.warnings()).anyMatch(w -> w instanceof InferenceWarning.MixedTypeFallback);
    }

    @Test
    void lowCardinalitySkipsRandom() {
        // 5 observations but only 2 distinct values (ratio 0.4 < 0.5 default)
        ShapeNode root = leaf("status", "A", "A", "A", "B", "B");
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        ShapeNode status = childOf(root, "status");
        assertThat(status.directive()).isEmpty();
    }

    @Test
    void existingDirectiveIsNotOverwritten() {
        ShapeNode root = leaf("qty", "1", "5", "3", "7", "2");
        ShapeNode qty = childOf(root, "qty");
        qty.setDirective(new Directive.Repeat(2, 5));  // pre-existing
        new RandomRangeAnalyzer().analyze(root, new AnalysisContext(InferenceConfig.defaults()));
        assertThat(qty.directive()).contains(new Directive.Repeat(2, 5));
    }

    private ShapeNode leaf(String name, String... values) {
        ShapeNode root = new ShapeNode(new QName("root"), "/root");
        ShapeNode child = new ShapeNode(new QName(name), "/root/" + name);
        root.orderedContent().add(new ContentItem.ChildSlot(child));
        for (String v : values) child.valueSamples().add(v);
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
