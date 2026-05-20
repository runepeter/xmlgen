package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.AnalysisContext;
import org.brylex.xmlgen.infer.Analyzer;
import org.brylex.xmlgen.infer.ContentItem;
import org.brylex.xmlgen.infer.Directive;
import org.brylex.xmlgen.infer.InferenceWarning;
import org.brylex.xmlgen.infer.Signature;
import org.brylex.xmlgen.infer.ShapeNode;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups parent-instances by their ordered child-signature and emits {@link Directive.Choose}
 * directives where structural variation is detected.
 *
 * <p>Detection algorithm:
 * <ol>
 *   <li>For each parent node, collect per-instance {@link Signature}s from
 *       {@link AnalysisContext#signaturesAt(String)}.</li>
 *   <li>Group and count by signature. Drop signatures with fewer than
 *       {@code chooseNoiseThreshold} of observations — each drop emits a
 *       {@link InferenceWarning.DroppedRareSignature}.</li>
 *   <li>After dropping: one remaining signature → no-op.</li>
 *   <li>Check for subset-chain (all smaller sigs are strict subsets of the largest).
 *       If yes, emit a standard {@link Directive.Choose} (v1 simplification — renderer
 *       refines this in Task 19).</li>
 *   <li>Otherwise emit a {@link Directive.Choose} with one branch per signature,
 *       weight = observation count.</li>
 *   <li>If branches exceed {@code chooseMaxBranches}, cap at max, emit
 *       {@link InferenceWarning.ChooseBranchOverflow}, keep top-N by count.</li>
 * </ol>
 *
 * <p>Integration note: {@link AnalysisContext#signaturesAt(String)} is populated by ShapeBuilder
 * (wired in Task 20). Unit tests supply data directly via the four-arg
 * {@link AnalysisContext} constructor.
 */
public class ChooseAnalyzer implements Analyzer {

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root, ctx);
    }

    private void walk(ShapeNode node, AnalysisContext ctx) {
        List<Signature> sigs = ctx.signaturesAt(node.xpath());
        if (!sigs.isEmpty()) {
            classify(node, sigs, ctx);
        }
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                walk(cs.node(), ctx);
            }
        }
    }

    private void classify(ShapeNode parent, List<Signature> sigs, AnalysisContext ctx) {
        // Group signatures by identity and count occurrences
        Map<Signature, Integer> counts = new LinkedHashMap<>();
        for (Signature s : sigs) {
            counts.merge(s, 1, Integer::sum);
        }
        int total = sigs.size();

        // Drop signatures below noise threshold
        double noise = ctx.config().chooseNoiseThreshold();
        List<Map.Entry<Signature, Integer>> kept = new ArrayList<>();
        for (Map.Entry<Signature, Integer> e : counts.entrySet()) {
            double ratio = (double) e.getValue() / total;
            if (ratio < noise) {
                ctx.warn(new InferenceWarning.DroppedRareSignature(
                        parent.xpath(), e.getKey().repr(), e.getValue(), total));
            } else {
                kept.add(e);
            }
        }

        if (kept.size() <= 1) {
            return; // Single signature after filtering — nothing to choose between
        }

        // Detect subset-chain
        if (isSubsetChain(kept)) {
            applySubsetChainChoose(parent, kept, ctx);
            return;
        }

        // Mutually distinct signatures — emit Choose at parent level
        if (kept.size() > ctx.config().chooseMaxBranches()) {
            ctx.warn(new InferenceWarning.ChooseBranchOverflow(
                    parent.xpath(), kept.size(), ctx.config().chooseMaxBranches()));
            // Keep top-N by count, stable order (most frequent first)
            kept.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            kept = kept.subList(0, ctx.config().chooseMaxBranches());
        }

        List<Directive.Branch> branches = new ArrayList<>();
        for (Map.Entry<Signature, Integer> e : kept) {
            List<ContentItem> body = e.getKey().bodyFor(parent);
            branches.add(new Directive.Branch(e.getValue(), body));
        }
        parent.setDirective(new Directive.Choose(branches));
    }

    /**
     * Determines whether the kept signatures form a strict subset chain — i.e. each
     * smaller signature's qName set is a strict subset of every larger signature's qName set.
     *
     * <p>The chain is checked by sorting signatures by slot count (ascending) and verifying
     * that each smaller signature's qNames are all present in the next larger signature.
     */
    private boolean isSubsetChain(List<Map.Entry<Signature, Integer>> kept) {
        if (kept.size() < 2) return false;

        // Sort by slot count ascending
        List<Signature> sorted = kept.stream()
                .map(Map.Entry::getKey)
                .sorted((a, b) -> Integer.compare(a.slots().size(), b.slots().size()))
                .toList();

        // Verify each is a strict subset of the next (by qName set)
        for (int i = 0; i < sorted.size() - 1; i++) {
            Set<QName> smaller = qNameSet(sorted.get(i));
            Set<QName> larger = qNameSet(sorted.get(i + 1));
            if (!larger.containsAll(smaller) || larger.equals(smaller)) {
                return false; // Not a strict subset
            }
        }
        return true;
    }

    private Set<QName> qNameSet(Signature sig) {
        Set<QName> names = new HashSet<>();
        for (Signature.Slot slot : sig.slots()) {
            if (slot.cardinalityBucket() > 0) {
                names.add(slot.qName());
            }
        }
        return names;
    }

    /**
     * Handles subset-chain: v1 emits a standard {@link Directive.Choose} (one branch per
     * distinct signature group). The renderer (Task 19) refines how this renders in the
     * optional-element case; at this stage the structure is correct and warnings/weights
     * are meaningful.
     */
    private void applySubsetChainChoose(ShapeNode parent,
                                        List<Map.Entry<Signature, Integer>> kept,
                                        AnalysisContext ctx) {
        // Cap branches if needed
        if (kept.size() > ctx.config().chooseMaxBranches()) {
            ctx.warn(new InferenceWarning.ChooseBranchOverflow(
                    parent.xpath(), kept.size(), ctx.config().chooseMaxBranches()));
            List<Map.Entry<Signature, Integer>> mutable = new ArrayList<>(kept);
            mutable.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            kept = mutable.subList(0, ctx.config().chooseMaxBranches());
        }

        List<Directive.Branch> branches = new ArrayList<>();
        for (Map.Entry<Signature, Integer> e : kept) {
            List<ContentItem> body = e.getKey().bodyFor(parent);
            branches.add(new Directive.Branch(e.getValue(), body));
        }
        parent.setDirective(new Directive.Choose(branches));
    }
}
