package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public class RandomRangeAnalyzer implements Analyzer {

    private static final Pattern UUID_RX = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root, ctx);
    }

    private void walk(ShapeNode node, AnalysisContext ctx) {
        if (node.directive().isEmpty() && !node.valueSamples().isEmpty()) {
            tryAssign(node, ctx);
        }
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) walk(cs.node(), ctx);
        }
    }

    private void tryAssign(ShapeNode node, AnalysisContext ctx) {
        InferenceConfig cfg = ctx.config();
        int total = node.valueSamples().total();
        int distinct = node.valueSamples().distinctCount();
        if (total == 0) return;
        double ratio = (double) distinct / total;
        if (ratio < cfg.randomCardinalityThreshold()) {
            return;
        }

        // UUID first (highest specificity)
        if (node.valueSamples().distinct().stream().allMatch(v -> UUID_RX.matcher(v).matches())) {
            node.setDirective(new Directive.Random(new Range.Uuid()));
            return;
        }

        // Integer
        try {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (String v : node.valueSamples().distinct()) {
                long l = Long.parseLong(v.trim());
                if (l < min) min = l;
                if (l > max) max = l;
            }
            node.setDirective(new Directive.Random(new Range.IntRange(min, max)));
            return;
        } catch (NumberFormatException ignored) {}

        // BigDecimal
        try {
            BigDecimal min = null, max = null;
            int scale = 0;
            for (String v : node.valueSamples().distinct()) {
                BigDecimal d = new BigDecimal(v.trim());
                scale = Math.max(scale, d.scale());
                if (min == null || d.compareTo(min) < 0) min = d;
                if (max == null || d.compareTo(max) > 0) max = d;
            }
            node.setDirective(new Directive.Random(new Range.AmountRange(min, max, scale)));
            return;
        } catch (NumberFormatException ignored) {}

        // ISO date
        try {
            LocalDate min = null, max = null;
            for (String v : node.valueSamples().distinct()) {
                LocalDate d = LocalDate.parse(v.trim());
                if (min == null || d.isBefore(min)) min = d;
                if (max == null || d.isAfter(max)) max = d;
            }
            node.setDirective(new Directive.Random(new Range.DateRange(min, max)));
            return;
        } catch (DateTimeParseException ignored) {}

        ctx.warn(new InferenceWarning.MixedTypeFallback(node.xpath(),
                "could not parse all observed values as int/decimal/date/uuid"));
    }
}
