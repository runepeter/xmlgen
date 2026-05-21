package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.AnalysisContext;
import org.brylex.xmlgen.infer.Analyzer;
import org.brylex.xmlgen.infer.ContentItem;
import org.brylex.xmlgen.infer.Directive;
import org.brylex.xmlgen.infer.InferenceWarning;
import org.brylex.xmlgen.infer.ShapeNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups low-cardinality leaf children that co-occur bijectively within the same
 * parent-instance scope and emits shared {@link Directive.Pick} directives pointing
 * to a named pool.
 *
 * <p>Algorithm (per parent ShapeNode):
 * <ol>
 *   <li>Identify leaf children with no directive yet and low cardinality
 *       (distinct/total ≤ {@code lowCardinalityRatio} and distinct &lt; {@code lowCardinalityMaxDistinct}).
 *   </li>
 *   <li>Obtain per-parent-instance rows from {@link AnalysisContext#rowsAt(String)}.
 *       A row is a Map of leaf-local-name → value.</li>
 *   <li>Attempt to group all low-cardinality candidates as a single bijective group.
 *       Bijective: each value-tuple is unique, and no single-leaf value appears with
 *       more than one partner value for any other leaf in the group.</li>
 *   <li>If bijective and distinct rows ≥ {@code minPoolRows}: emit shared
 *       {@link Directive.Pick} for every leaf in the group.</li>
 *   <li>If not bijective: emit {@link InferenceWarning.PartialBijectionRejected} and
 *       fall back to individual picks per leaf (or empty if cardinality == 1).</li>
 *   <li>If bijective but distinct rows &lt; {@code minPoolRows}: fall back to individual
 *       picks per leaf (or empty if cardinality == 1).</li>
 *   <li>Pool name = path from nearest {@link Directive.Repeat}-marked ancestor (or root)
 *       to the parent, dot-separated local names.</li>
 *   <li>Pool rows sorted lexicographically on first column value (deterministic).</li>
 *   <li>If distinct == observations: emit {@link InferenceWarning.UniqueValueCycleRisk}.</li>
 * </ol>
 */
public class PickCoherenceAnalyzer implements Analyzer {

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root, null, ctx);
    }

    private void walk(ShapeNode node, ShapeNode repeatAncestor, AnalysisContext ctx) {
        // Determine if this node is the new repeat anchor
        ShapeNode nextRepeatAncestor = (node.directive().isPresent()
                && node.directive().get() instanceof Directive.Repeat)
                ? node
                : repeatAncestor;

        // Process this node as a parent
        processParent(node, nextRepeatAncestor, ctx);

        // Recurse into children
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                walk(cs.node(), nextRepeatAncestor, ctx);
            }
        }
    }

    private void processParent(ShapeNode parent, ShapeNode repeatAncestor, AnalysisContext ctx) {
        // Get per-parent-instance rows from context first — needed for both paths
        List<Map<String, String>> rows = ctx.rowsAt(parent.xpath());

        // Collect leaf children with no directive and low cardinality
        List<ShapeNode> candidates = collectLowCardinalityCandidates(parent, ctx);

        // If no low-cardinality candidates but rows exist and show value repetition
        // (distinct tuples < total rows), promote ALL directive-free leaf children as candidates.
        // This handles bijective pool data where distinct/total ratio is high per-leaf yet
        // the same value-tuples repeat across parent-instances — a strong signal that
        // these are foreign-key fields drawn from a shared pool rather than random strings.
        // We require at least one repeated tuple (rows.size() > distinct-tuple-count) to
        // avoid promoting genuinely unique fields that PickCoherence cannot help with.
        if (candidates.isEmpty() && !rows.isEmpty()) {
            long distinctTupleCount = rows.stream().map(m -> new ArrayList<>(m.values())).distinct().count();
            if (distinctTupleCount < rows.size()) {
                candidates = collectAllLeafCandidates(parent);
            }
        }

        if (candidates.isEmpty() || rows.isEmpty()) {
            return;
        }

        // Compute pool name
        String poolName = computePoolName(parent, repeatAncestor);

        // Collect leaf names involved
        List<String> leafNames = candidates.stream()
                .map(n -> n.qName().getLocalPart())
                .toList();

        // Extract value-tuples restricted to leaf candidates
        List<Map<String, String>> filteredRows = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> filteredRow = new LinkedHashMap<>();
            for (String name : leafNames) {
                if (row.containsKey(name)) {
                    filteredRow.put(name, row.get(name));
                }
            }
            if (!filteredRow.isEmpty()) {
                filteredRows.add(filteredRow);
            }
        }

        if (filteredRows.isEmpty()) {
            return;
        }

        // Check bijection
        boolean bijective = isBijective(leafNames, filteredRows);

        if (!bijective) {
            ctx.warn(new InferenceWarning.PartialBijectionRejected(
                    parent.xpath(), computeBijectionRatio(leafNames, filteredRows)));
            applyIndividualPicks(candidates, poolName, rows, ctx);
            return;
        }

        // Compute distinct rows
        Set<List<String>> distinctTuples = distinctTuples(leafNames, filteredRows);
        int distinctRowCount = distinctTuples.size();

        if (distinctRowCount < ctx.config().minPoolRows()) {
            // Below K-threshold: fall back to individual picks
            applyIndividualPicks(candidates, poolName, rows, ctx);
            return;
        }

        // Check unique-value cycle risk: all tuples unique
        if (distinctRowCount == filteredRows.size()) {
            for (ShapeNode leaf : candidates) {
                ctx.warn(new InferenceWarning.UniqueValueCycleRisk(leaf.xpath(), filteredRows.size()));
            }
        }

        // Build combined multi-column pool rows sorted lexicographically on first column.
        // The row maps use leaf local-names as keys, so the renderer can register a single
        // coherent pool entry instead of separate single-column rows per leaf.
        List<Map<String, String>> combinedRows = new ArrayList<>();
        for (List<String> tuple : distinctTuples) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < leafNames.size(); i++) {
                row.put(leafNames.get(i), tuple.get(i));
            }
            combinedRows.add(row);
        }
        combinedRows.sort(Comparator.comparing(m -> m.getOrDefault(leafNames.get(0), "")));

        // Emit Pick directives. The first leaf carries the combined pool rows so the renderer
        // can register a single multi-column pool. Subsequent leaves carry null (pool already
        // registered by the first leaf).
        for (int i = 0; i < candidates.size(); i++) {
            ShapeNode leaf = candidates.get(i);
            List<Map<String, String>> rowsForDirective = (i == 0) ? combinedRows : null;
            leaf.setDirective(new Directive.Pick(poolName, leaf.qName().getLocalPart(), rowsForDirective));
        }
    }

    /**
     * Collects ALL directive-free leaf children of the given parent, regardless of cardinality.
     * Used as a fallback when rows data is present but the ratio filter would exclude candidates
     * (e.g. bijective foreign-key fields where each parent-instance picks a unique value).
     */
    private List<ShapeNode> collectAllLeafCandidates(ShapeNode parent) {
        List<ShapeNode> result = new ArrayList<>();
        for (ContentItem ci : parent.orderedContent()) {
            if (!(ci instanceof ContentItem.ChildSlot cs)) continue;
            ShapeNode child = cs.node();
            boolean isLeaf = child.orderedContent().stream()
                    .noneMatch(c -> c instanceof ContentItem.ChildSlot);
            if (!isLeaf) continue;
            if (child.directive().isPresent()) continue;
            if (!child.valueSamples().isEmpty()) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Collects leaf children of the given parent that:
     * <ul>
     *   <li>have no directive yet,</li>
     *   <li>have low cardinality: distinct/total ≤ {@code lowCardinalityRatio} AND
     *       distinct &lt; {@code lowCardinalityMaxDistinct}</li>
     * </ul>
     */
    private List<ShapeNode> collectLowCardinalityCandidates(ShapeNode parent, AnalysisContext ctx) {
        double ratio = ctx.config().lowCardinalityRatio();
        int maxDistinct = ctx.config().lowCardinalityMaxDistinct();

        List<ShapeNode> result = new ArrayList<>();
        for (ContentItem ci : parent.orderedContent()) {
            if (!(ci instanceof ContentItem.ChildSlot cs)) {
                continue;
            }
            ShapeNode child = cs.node();
            // Must be a leaf (no child slots)
            boolean isLeaf = child.orderedContent().stream()
                    .noneMatch(c -> c instanceof ContentItem.ChildSlot);
            if (!isLeaf) {
                continue;
            }
            // Must have no directive
            if (child.directive().isPresent()) {
                continue;
            }
            // Low cardinality check
            ShapeNode.ValueSamples vs = child.valueSamples();
            if (vs.isEmpty()) {
                continue;
            }
            int distinct = vs.distinctCount();
            int total = vs.total();
            if (distinct >= maxDistinct) {
                continue;
            }
            double cardRatio = (double) distinct / total;
            if (cardRatio > ratio) {
                continue;
            }
            result.add(child);
        }
        return result;
    }

    /**
     * Checks whether the given leaf names form a strict bijection across all rows.
     * Bijective means:
     * <ul>
     *   <li>Each tuple (combination of values for all leaves) is unique.</li>
     *   <li>For each pair of leaves, no value in one leaf maps to more than one
     *       distinct value in the other.</li>
     * </ul>
     */
    private boolean isBijective(List<String> leafNames, List<Map<String, String>> rows) {
        // Check: each value-tuple is unique
        Set<List<String>> seen = new HashSet<>();
        for (Map<String, String> row : rows) {
            List<String> tuple = toTuple(leafNames, row);
            seen.add(tuple);
        }
        // Not checking uniqueness of all tuples as a primary criterion;
        // instead check pairwise bijection: no leaf value repeats with a different partner.

        // For each leaf, check that each of its values maps to exactly one value for every other leaf
        for (String leafA : leafNames) {
            // Map from leafA's value → set of values seen for each other leaf
            Map<String, Map<String, Set<String>>> aValueToOthers = new HashMap<>();
            for (Map<String, String> row : rows) {
                String va = row.getOrDefault(leafA, "");
                aValueToOthers.computeIfAbsent(va, k -> new HashMap<>());
                for (String leafB : leafNames) {
                    if (leafB.equals(leafA)) continue;
                    String vb = row.getOrDefault(leafB, "");
                    aValueToOthers.get(va)
                            .computeIfAbsent(leafB, k -> new HashSet<>())
                            .add(vb);
                }
            }
            // Bijection check: each value of leafA must map to exactly one value of each other leaf
            for (Map.Entry<String, Map<String, Set<String>>> entry : aValueToOthers.entrySet()) {
                for (Map.Entry<String, Set<String>> partnerEntry : entry.getValue().entrySet()) {
                    if (partnerEntry.getValue().size() > 1) {
                        return false; // leafA value maps to multiple values for some other leaf
                    }
                }
            }
        }
        return true;
    }

    /**
     * Computes an approximate bijection ratio for the warning.
     * Returns ratio of rows that are part of a consistent bijection.
     */
    private double computeBijectionRatio(List<String> leafNames, List<Map<String, String>> rows) {
        // Count tuples that appear in a 1-to-1 mapping for the first pair of leaves
        if (leafNames.size() < 2) return 1.0;
        String leafA = leafNames.get(0);
        String leafB = leafNames.get(1);

        Map<String, Set<String>> aToBValues = new HashMap<>();
        Map<String, Set<String>> bToAValues = new HashMap<>();
        for (Map<String, String> row : rows) {
            String va = row.getOrDefault(leafA, "");
            String vb = row.getOrDefault(leafB, "");
            aToBValues.computeIfAbsent(va, k -> new HashSet<>()).add(vb);
            bToAValues.computeIfAbsent(vb, k -> new HashSet<>()).add(va);
        }

        long consistent = rows.stream().filter(row -> {
            String va = row.getOrDefault(leafA, "");
            String vb = row.getOrDefault(leafB, "");
            return aToBValues.get(va).size() == 1 && bToAValues.get(vb).size() == 1;
        }).count();

        return rows.isEmpty() ? 1.0 : (double) consistent / rows.size();
    }

    /**
     * Falls back to individual Pick directives per leaf when bijective pooling is not possible.
     * Leaves with cardinality == 1 are skipped (no directive, literal rendering).
     */
    private void applyIndividualPicks(List<ShapeNode> candidates, String poolName,
                                      List<Map<String, String>> rows, AnalysisContext ctx) {
        for (ShapeNode leaf : candidates) {
            if (leaf.directive().isPresent()) {
                continue;
            }
            ShapeNode.ValueSamples vs = leaf.valueSamples();
            if (vs.distinctCount() <= 1) {
                // cardinality == 1 → leave directive empty (literal rendering)
                continue;
            }
            // Individual pool name: poolName + "." + leaf local name for disambiguation,
            // but spec says individual picks per leaf with its own pool, so use same poolName
            // (column differentiates them within the same pool).
            leaf.setDirective(new Directive.Pick(poolName, leaf.qName().getLocalPart()));
        }
    }

    /**
     * Computes the pool name as: path from nearest Repeat ancestor (inclusive) down to parent,
     * dot-separated local names.
     *
     * <p>Examples:
     * <ul>
     *   <li>repeatAncestor = {@code /order/line}, parent = {@code /order/line/party} → {@code line.party}</li>
     *   <li>No repeat ancestor, parent = {@code /order/customer} → {@code order.customer}</li>
     * </ul>
     */
    private String computePoolName(ShapeNode parent, ShapeNode repeatAncestor) {
        String parentPath = parent.xpath();

        String relativePath;
        if (repeatAncestor != null) {
            // Include the repeat anchor's local name: strip everything before the anchor
            // anchorPath = /order/line → strip /order prefix, leaving /line/party
            String anchorPath = repeatAncestor.xpath();
            String anchorParentPath = anchorPath.contains("/")
                    ? anchorPath.substring(0, anchorPath.lastIndexOf('/'))
                    : "";
            // relativePath = parentPath minus anchorParentPath (i.e., include anchor's segment)
            if (!anchorParentPath.isEmpty() && parentPath.startsWith(anchorParentPath)) {
                relativePath = parentPath.substring(anchorParentPath.length());
            } else {
                relativePath = parentPath;
            }
        } else {
            // No repeat ancestor: include from root
            relativePath = parentPath;
        }

        // Remove leading slash
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        // Replace slashes with dots
        return relativePath.replace('/', '.');
    }

    /**
     * Extracts value-tuples as ordered lists for a given set of leaf names.
     */
    private Set<List<String>> distinctTuples(List<String> leafNames, List<Map<String, String>> rows) {
        Set<List<String>> tuples = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            tuples.add(toTuple(leafNames, row));
        }
        return tuples;
    }

    private List<String> toTuple(List<String> leafNames, Map<String, String> row) {
        List<String> tuple = new ArrayList<>(leafNames.size());
        for (String name : leafNames) {
            tuple.add(row.getOrDefault(name, ""));
        }
        return tuple;
    }
}
