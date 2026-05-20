package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.AnalysisContext;
import org.brylex.xmlgen.infer.Analyzer;
import org.brylex.xmlgen.infer.ContentItem;
import org.brylex.xmlgen.infer.Directive;
import org.brylex.xmlgen.infer.ShapeNode;

import java.util.List;

/**
 * Detects monotonically increasing integer sequences in leaves that sit under a
 * {@link Directive.Repeat} parent.
 *
 * <p>The analyzer walks the merged shape tree. For every leaf that:
 * <ol>
 *   <li>has at least one ancestor with {@code Directive.Repeat}, and</li>
 *   <li>has no directive already assigned, and</li>
 *   <li>has iteration-value sequences available in the {@link AnalysisContext}</li>
 * </ol>
 * it checks whether all sequences share a consistent positive integer step. If yes,
 * it assigns {@code Directive.Increment(step, minStartingValue)}.
 *
 * <p>Requires {@link RepeatAnalyzer} to have run first so that {@code Directive.Repeat}
 * is already set on the relevant parent nodes.
 *
 * <p>Integration note: {@link AnalysisContext#iterationValuesAt(String)} is populated
 * by {@code ShapeBuilder} iteration-value capture, wired up in Task 20. Unit tests
 * supply the sequences directly via the three-arg {@code AnalysisContext} constructor.
 */
public class IncrementAnalyzer implements Analyzer {

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root, ctx, false);
    }

    private void walk(ShapeNode node, AnalysisContext ctx, boolean underRepeat) {
        boolean enteringRepeat = underRepeat;
        if (node.directive().isPresent() && node.directive().get() instanceof Directive.Repeat) {
            enteringRepeat = true;
        }

        boolean isLeaf = node.orderedContent().stream()
                .noneMatch(ci -> ci instanceof ContentItem.ChildSlot);

        if (isLeaf && underRepeat && node.directive().isEmpty()) {
            tryIncrement(node, ctx);
        }

        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                walk(cs.node(), ctx, enteringRepeat);
            }
        }
    }

    private void tryIncrement(ShapeNode node, AnalysisContext ctx) {
        List<List<String>> sequences = ctx.iterationValuesAt(node.xpath());
        if (sequences.isEmpty()) return;

        Integer commonStep = null;
        int minStart = Integer.MAX_VALUE;
        boolean anyUsableSequence = false;

        for (List<String> seq : sequences) {
            if (seq.size() < 2) continue; // single-value sequence can't establish step

            try {
                int first = Integer.parseInt(seq.get(0).trim());
                int prev = first;
                int step = Integer.parseInt(seq.get(1).trim()) - prev;
                if (step <= 0) return; // not strictly increasing

                for (int i = 1; i < seq.size(); i++) {
                    int cur = Integer.parseInt(seq.get(i).trim());
                    if (cur - prev != step) return; // inconsistent step within this sequence
                    prev = cur;
                }

                if (commonStep == null) {
                    commonStep = step;
                } else if (commonStep != step) {
                    return; // step differs across sequences
                }

                if (first < minStart) minStart = first;
                anyUsableSequence = true;

            } catch (NumberFormatException e) {
                return; // non-numeric — bail out entirely
            }
        }

        if (commonStep != null && anyUsableSequence) {
            node.setDirective(new Directive.Increment(commonStep, minStart));
        }
    }
}
