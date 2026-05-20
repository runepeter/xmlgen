package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.AnalysisContext;
import org.brylex.xmlgen.infer.ContentItem;
import org.brylex.xmlgen.infer.Directive;
import org.brylex.xmlgen.infer.InferenceConfig;
import org.brylex.xmlgen.infer.InferenceWarning;
import org.brylex.xmlgen.infer.Range;
import org.brylex.xmlgen.infer.ShapeNode;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PickCoherenceAnalyzer}.
 *
 * All tests supply row data directly via the five-arg {@link AnalysisContext} constructor.
 * Real ShapeBuilder population is wired up in Task 20.
 *
 * <p>To pass the low-cardinality filter (distinct/total ≤ 0.20), value samples are designed
 * so that each of the K distinct values appears multiple times across parent-instance observations.
 * For example, 6 distinct values each observed 3 times → distinct=6, total=18, ratio=6/18≈0.33
 * which is still too high. We use 6 distinct with each repeated enough: 6/30 = 0.20. Exactly 0.20
 * passes since the filter is ≤ 0.20. So we use repeat-count = distinct / ratio = 6 / 0.20 = 30 total.
 *
 * <p>Simpler: 6 distinct values, each observed 30 times: total=180, ratio=6/180≈0.033 → passes.
 * Rows: the per-parent-instance row-list drives the bijection check and pool detection.
 */
class PickCoherenceAnalyzerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a tree path based on xpath, optionally marking the first intermediate
     * segment (index 2 in parts) with Repeat. Returns the root ShapeNode.
     */
    private ShapeNode buildTree(String parentXpath, boolean repeatOnFirstChild, String... leafNames) {
        String[] parts = parentXpath.split("/");
        // parts[0] is empty (before leading slash)

        ShapeNode root = new ShapeNode(new QName(parts[1]), "/" + parts[1]);
        ShapeNode current = root;

        for (int i = 2; i < parts.length; i++) {
            String seg = parts[i];
            String xpath = current.xpath() + "/" + seg;
            ShapeNode child = new ShapeNode(new QName(seg), xpath);
            current.orderedContent().add(new ContentItem.ChildSlot(child));
            if (i == 2 && repeatOnFirstChild) {
                child.setDirective(new Directive.Repeat(2, 5));
            }
            current = child;
        }

        // 'current' is now the parent node — add leaves
        for (String leafName : leafNames) {
            ShapeNode leaf = new ShapeNode(new QName(leafName), current.xpath() + "/" + leafName);
            current.orderedContent().add(new ContentItem.ChildSlot(leaf));
        }

        return root;
    }

    /** Finds a node by xpath in the tree. */
    private ShapeNode findNode(ShapeNode root, String xpath) {
        if (root.xpath().equals(xpath)) return root;
        for (ContentItem ci : root.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                ShapeNode found = findNode(cs.node(), xpath);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Creates a row map for a set of leaf names and values (alternating name/value). */
    private Map<String, String> row(String... nameValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            map.put(nameValues[i], nameValues[i + 1]);
        }
        return map;
    }

    /**
     * Populates a leaf's valueSamples so that it has {@code distinctCount} distinct values,
     * each observed {@code repeatEach} times. Returns the distinct value array.
     */
    private String[] populateLeaf(ShapeNode leaf, String prefix, int distinctCount, int repeatEach) {
        String[] values = new String[distinctCount];
        for (int i = 0; i < distinctCount; i++) {
            values[i] = prefix + String.format("%02d", i + 1);
            for (int j = 0; j < repeatEach; j++) {
                leaf.valueSamples().add(values[i]);
            }
        }
        return values;
    }

    /**
     * Builds rows for a bijective (name, iban) group: each pair repeated {@code repeatEach} times.
     */
    private List<Map<String, String>> bijectiveRows(String[] names, String[] ibans, int repeatEach) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < repeatEach; j++) {
                rows.add(row("name", names[i], "iban", ibans[i]));
            }
        }
        return rows;
    }

    /** Builds AnalysisContext with row data supplied per parent xpath. */
    private AnalysisContext ctxWithRows(Map<String, List<Map<String, String>>> rowsPerXpath) {
        return new AnalysisContext(
                InferenceConfig.defaults(), List.of(), Map.of(), Map.of(), rowsPerXpath);
    }

    private AnalysisContext ctxWithRows(InferenceConfig cfg,
                                        Map<String, List<Map<String, String>>> rowsPerXpath) {
        return new AnalysisContext(cfg, List.of(), Map.of(), Map.of(), rowsPerXpath);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Bijective group with ≥ K (default 5) distinct rows → both leaves get Pick.
     * 6 distinct (name, iban) pairs, each repeated 5 times:
     * distinct=6, total=30, ratio=6/30=0.20 → low cardinality.
     */
    @Test
    void bijectiveGroupAboveKThresholdGetsPick() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode party = findNode(root, "/order/line/party");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // 6 distinct values, each repeated 5 times: ratio = 6/30 = 0.20 → passes
        String[] names = populateLeaf(name, "Party", 6, 5);
        String[] ibans = populateLeaf(iban, "NO", 6, 5);

        List<Map<String, String>> rows = bijectiveRows(names, ibans, 5);

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        assertThat(name.directive()).isPresent();
        assertThat(iban.directive()).isPresent();
        assertThat(name.directive().get()).isInstanceOf(Directive.Pick.class);
        assertThat(iban.directive().get()).isInstanceOf(Directive.Pick.class);

        Directive.Pick namePick = (Directive.Pick) name.directive().get();
        Directive.Pick ibanPick = (Directive.Pick) iban.directive().get();
        assertThat(namePick.column()).isEqualTo("name");
        assertThat(ibanPick.column()).isEqualTo("iban");
        // Same pool name for both
        assertThat(namePick.poolName()).isEqualTo(ibanPick.poolName());
    }

    /**
     * Pool name starts with the Repeat ancestor's local name.
     * /order/line/party under <line gen:repeat> → pool = "line.party"
     */
    @Test
    void poolNameStartsWithRepeatAncestor() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        String[] names = populateLeaf(name, "Party", 6, 5);
        String[] ibans = populateLeaf(iban, "NO", 6, 5);
        List<Map<String, String>> rows = bijectiveRows(names, ibans, 5);

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        assertThat(name.directive()).isPresent();
        Directive.Pick namePick = (Directive.Pick) name.directive().get();
        assertThat(namePick.poolName()).isEqualTo("line.party");
    }

    /**
     * No Repeat ancestor → pool name goes from root to parent.
     * /order/customer with no Repeat → pool = "order.customer"
     */
    @Test
    void poolNameWithNoRepeatAncestorUsesRootPath() {
        ShapeNode root = buildTree("/order/customer", false, "name", "id");
        ShapeNode name = findNode(root, "/order/customer/name");
        ShapeNode id = findNode(root, "/order/customer/id");

        String[] names = populateLeaf(name, "Party", 6, 5);
        String[] ids = populateLeaf(id, "C", 6, 5);
        List<Map<String, String>> rows = bijectiveRows(names, ids, 5);
        // Adjust rows to use 'id' column
        List<Map<String, String>> adjustedRows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < 5; j++) {
                adjustedRows.add(row("name", names[i], "id", ids[i]));
            }
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/customer", adjustedRows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        assertThat(name.directive()).isPresent();
        Directive.Pick namePick = (Directive.Pick) name.directive().get();
        assertThat(namePick.poolName()).isEqualTo("order.customer");
    }

    /**
     * Below K rows (3 distinct pairs, K=5): no shared pool; fall back to individual picks.
     * Values: 3 distinct, each repeated 10 times: ratio=3/30=0.10 → low cardinality, passes.
     * But only 3 distinct rows → below K=5 → individual picks.
     */
    @Test
    void belowKThresholdFallsToIndividualPicks() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // 3 distinct values, each repeated 10 times: ratio=3/30=0.10 → passes low card
        String[] names = populateLeaf(name, "Party", 3, 10);
        String[] ibans = populateLeaf(iban, "NO", 3, 10);

        List<Map<String, String>> rows = bijectiveRows(names, ibans, 10);

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // No PartialBijectionRejected warning (IS bijective, just below K)
        assertThat(ctx.warnings()).noneMatch(w -> w instanceof InferenceWarning.PartialBijectionRejected);

        // Individual picks (cardinality > 1 for both leaves)
        assertThat(name.directive()).isPresent();
        assertThat(iban.directive()).isPresent();
        assertThat(name.directive().get()).isInstanceOf(Directive.Pick.class);
        assertThat(iban.directive().get()).isInstanceOf(Directive.Pick.class);
    }

    /**
     * Below K with cardinality == 1: leave directive empty (literal rendering).
     */
    @Test
    void belowKWithCardinalityOneLeavesDirecitveEmpty() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // Only 1 distinct value each (cardinality == 1)
        // ratio = 1/20 = 0.05 → passes low cardinality, but distinctCount == 1
        for (int i = 0; i < 20; i++) name.valueSamples().add("Acme");
        for (int i = 0; i < 20; i++) iban.valueSamples().add("NO001");

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("name", "Acme", "iban", "NO001"));

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // Both leaves have cardinality 1 → no directive (literal rendering)
        assertThat(name.directive()).isEmpty();
        assertThat(iban.directive()).isEmpty();
    }

    /**
     * Partial bijection (name=Party01 appears with both ibans NO01 and NO02):
     * no shared pool, PartialBijectionRejected warning emitted.
     */
    @Test
    void partialBijectionEmitsWarningAndFallsBack() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // 6 distinct names, 6 distinct ibans; ratio must pass low cardinality check:
        // 6 distinct / 30 total = 0.20 → passes
        String[] names = populateLeaf(name, "Party", 6, 5);
        String[] ibans = populateLeaf(iban, "NO", 6, 5);

        // Now create rows where Party01 maps to both NO01 AND NO02 → not bijective
        List<Map<String, String>> rows = new ArrayList<>();
        // Party01 → NO01 (3 times)
        for (int i = 0; i < 3; i++) rows.add(row("name", names[0], "iban", ibans[0]));
        // Party01 → NO02 (2 times) ← breaks bijection
        for (int i = 0; i < 2; i++) rows.add(row("name", names[0], "iban", ibans[1]));
        // Other pairs bijectively
        for (int i = 2; i < 6; i++) {
            for (int j = 0; j < 5; j++) {
                rows.add(row("name", names[i], "iban", ibans[i]));
            }
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // PartialBijectionRejected warning must be emitted
        assertThat(ctx.warnings())
                .anyMatch(w -> w instanceof InferenceWarning.PartialBijectionRejected);
        InferenceWarning.PartialBijectionRejected rejected = ctx.warnings().stream()
                .filter(w -> w instanceof InferenceWarning.PartialBijectionRejected)
                .map(w -> (InferenceWarning.PartialBijectionRejected) w)
                .findFirst().orElseThrow();
        assertThat(rejected.xpath()).isEqualTo("/order/line/party");
    }

    /**
     * All values unique (distinct == observations, i.e. each tuple appears exactly once):
     * UniqueValueCycleRisk warning emitted.
     *
     * <p>For low cardinality to pass, we need distinct/total ≤ 0.20. Since every tuple is unique,
     * every row has a distinct name-value. So 6 distinct names / 6 total = 1.0 → fails the
     * low-cardinality filter normally. We adjust the config to raise the threshold.
     *
     * <p>Alternatively: use a custom config with lowCardinalityRatio=1.0 so all leaves qualify,
     * then all 6 unique-value leaves pass through to bijection and uniqueness check.
     */
    @Test
    void allUniqueValuesEmitsUniqueValueCycleRiskWarning() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // 6 fully unique pairs; each value appears exactly once
        String[] names = {"Acme", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"};
        String[] ibans = {"NO001", "NO002", "NO003", "NO004", "NO005", "NO006"};
        for (String n : names) name.valueSamples().add(n);
        for (String i : ibans) iban.valueSamples().add(i);

        // Each row is unique → 6 observations, 6 distinct tuples
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(row("name", names[i], "iban", ibans[i]));

        // Use a config with elevated lowCardinalityRatio so all-unique values still qualify
        InferenceConfig cfg = new InferenceConfig(
                1.0,   // lowCardinalityRatio — allow even 100% unique
                50,    // lowCardinalityMaxDistinct
                0.50,  // randomCardinalityThreshold
                5,     // minPoolRows
                0.10,  // chooseNoiseThreshold
                4,     // chooseMaxBranches
                1000,  // maxValueSamplesPerNode
                100,   // maxSampleDepth
                false, // allowVariableAttributes
                false  // strictMode
        );

        AnalysisContext ctx = ctxWithRows(cfg, Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // UniqueValueCycleRisk warnings must be emitted (one per leaf)
        List<InferenceWarning> cycleRisks = ctx.warnings().stream()
                .filter(w -> w instanceof InferenceWarning.UniqueValueCycleRisk)
                .toList();
        assertThat(cycleRisks).isNotEmpty();
    }

    /**
     * Lex-sorted pool rows: rows supplied in Z→A order still produce valid Pick directives.
     * Verifies the group is accepted and sorting doesn't break bijection detection.
     */
    @Test
    void poolRowsAreLexSortedOnFirstColumn() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // 6 distinct pairs, each repeated 5 times, supplied in reverse lex order
        String[] names = {"Zeta", "Epsilon", "Delta", "Gamma", "Beta", "Alpha"};
        String[] ibans = {"NO006", "NO005", "NO004", "NO003", "NO002", "NO001"};
        for (String n : names) for (int j = 0; j < 5; j++) name.valueSamples().add(n);
        for (String i : ibans) for (int j = 0; j < 5; j++) iban.valueSamples().add(i);

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < 5; j++) {
                rows.add(row("name", names[i], "iban", ibans[i]));
            }
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // Picks should be assigned (bijective, ≥ 5 rows)
        assertThat(name.directive()).isPresent();
        assertThat(iban.directive()).isPresent();
        assertThat(name.directive().get()).isInstanceOf(Directive.Pick.class);
        assertThat(iban.directive().get()).isInstanceOf(Directive.Pick.class);
    }

    /**
     * Leaf with an existing directive is skipped from candidacy.
     */
    @Test
    void leafWithExistingDirectiveIsSkipped() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // Pre-set directive on 'name' — it should be skipped as a candidate
        name.setDirective(new Directive.Random(new Range.Uuid()));

        // iban gets low-cardinality samples: 6 distinct / 30 total
        String[] ibanVals = populateLeaf(iban, "NO", 6, 5);
        // name also needs samples (but it has a directive, so it's skipped)
        for (int i = 0; i < 30; i++) name.valueSamples().add("ignored-" + (i % 6));

        // Rows for /order/line/party: only iban is a candidate
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 5; j++) {
                rows.add(row("name", "ignored-" + i, "iban", ibanVals[i]));
            }
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // name's directive should remain the pre-set Random directive
        assertThat(name.directive()).isPresent();
        assertThat(name.directive().get()).isInstanceOf(Directive.Random.class);
        // iban should get a pick (only candidate → individual pick, but ≥ 6 distinct is below K)
        // With single leaf: trivially bijective but distinctCount=6 ≥ minPoolRows=5 → Pick
        assertThat(iban.directive()).isPresent();
        assertThat(iban.directive().get()).isInstanceOf(Directive.Pick.class);
    }

    /**
     * High cardinality leaf (distinct/total > 0.20): skipped.
     */
    @Test
    void highCardinalityLeafIsSkipped() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // 10 distinct / 10 total = 100% > 20% → high cardinality, should be excluded
        for (int i = 0; i < 10; i++) name.valueSamples().add("Name" + i);
        for (int i = 0; i < 10; i++) iban.valueSamples().add("NO" + String.format("%03d", i));

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row("name", "Name" + i, "iban", "NO" + String.format("%03d", i)));
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // Both leaves excluded → no directive
        assertThat(name.directive()).isEmpty();
        assertThat(iban.directive()).isEmpty();
    }

    /**
     * Single low-cardinality leaf (only candidate): with ≥ minPoolRows distinct,
     * gets an individual Pick since the single-leaf group is trivially bijective.
     */
    @Test
    void singleLowCardinalityLeafWithEnoughDistinctGetsIndividualPick() {
        // Tree: /order/line/party with only one leaf: name
        ShapeNode root = buildTree("/order/line/party", true, "name");
        ShapeNode name = findNode(root, "/order/line/party/name");

        // 6 distinct, each repeated 5 times: ratio=6/30=0.20 → passes
        String[] names = populateLeaf(name, "Party", 6, 5);

        // Rows: one column (name only)
        List<Map<String, String>> rows = new ArrayList<>();
        for (String n : names) {
            for (int j = 0; j < 5; j++) rows.add(row("name", n));
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // 6 distinct ≥ K=5, single leaf → Pick
        assertThat(name.directive()).isPresent();
        assertThat(name.directive().get()).isInstanceOf(Directive.Pick.class);
    }

    /**
     * Single low-cardinality leaf below K: gets individual Pick (cardinality > 1).
     */
    @Test
    void singleLowCardinalityLeafBelowKGetsIndividualPickIfCardinalityGtOne() {
        ShapeNode root = buildTree("/order/line/party", true, "name");
        ShapeNode name = findNode(root, "/order/line/party/name");

        // 3 distinct, each repeated 10 times: ratio=3/30=0.10 → passes
        String[] names = populateLeaf(name, "Party", 3, 10);

        List<Map<String, String>> rows = new ArrayList<>();
        for (String n : names) {
            for (int j = 0; j < 10; j++) rows.add(row("name", n));
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/line/party", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // Below K=5 distinct → individual pick (cardinality > 1)
        assertThat(name.directive()).isPresent();
        assertThat(name.directive().get()).isInstanceOf(Directive.Pick.class);
    }

    /**
     * Analyzer recurses: picks are applied to nodes deep in the tree.
     */
    @Test
    void analyzerWalksTreeRecursively() {
        // /order → /order/customer with name, id; no Repeat anywhere
        ShapeNode root = buildTree("/order/customer", false, "name", "id");
        ShapeNode name = findNode(root, "/order/customer/name");
        ShapeNode id = findNode(root, "/order/customer/id");

        String[] names = populateLeaf(name, "Party", 6, 5);
        String[] ids = populateLeaf(id, "C", 6, 5);

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < 5; j++) {
                rows.add(row("name", names[i], "id", ids[i]));
            }
        }

        AnalysisContext ctx = ctxWithRows(Map.of("/order/customer", rows));
        new PickCoherenceAnalyzer().analyze(root, ctx);

        // Root itself has no directive
        assertThat(root.directive()).isEmpty();
        // But leaves under /order/customer get Pick
        assertThat(name.directive()).isPresent();
        assertThat(id.directive()).isPresent();
    }

    /**
     * No rows in context → no directive set (graceful no-op).
     */
    @Test
    void emptyRowsInContextIsNoOp() {
        ShapeNode root = buildTree("/order/line/party", true, "name", "iban");
        ShapeNode name = findNode(root, "/order/line/party/name");
        ShapeNode iban = findNode(root, "/order/line/party/iban");

        // Low-cardinality samples: 2 distinct / 20 total = 0.10 → passes
        for (int i = 0; i < 20; i++) name.valueSamples().add(i < 10 ? "Acme" : "Beta");
        for (int i = 0; i < 20; i++) iban.valueSamples().add(i < 10 ? "NO001" : "NO002");

        // No rows for /order/line/party in context → should no-op
        AnalysisContext ctx = ctxWithRows(Map.of());
        new PickCoherenceAnalyzer().analyze(root, ctx);

        assertThat(name.directive()).isEmpty();
        assertThat(iban.directive()).isEmpty();
    }
}
