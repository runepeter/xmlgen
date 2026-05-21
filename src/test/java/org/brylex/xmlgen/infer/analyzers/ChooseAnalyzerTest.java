package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.AnalysisContext;
import org.brylex.xmlgen.infer.ContentItem;
import org.brylex.xmlgen.infer.Directive;
import org.brylex.xmlgen.infer.InferenceConfig;
import org.brylex.xmlgen.infer.InferenceWarning;
import org.brylex.xmlgen.infer.Signature;
import org.brylex.xmlgen.infer.ShapeNode;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChooseAnalyzer}. All tests supply signature data directly via
 * the four-arg {@link AnalysisContext} constructor, matching the IncrementAnalyzer pattern.
 * Real ShapeBuilder population is wired up in Task 20.
 */
class ChooseAnalyzerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a simple tree: parent node with named child slots. */
    private ShapeNode buildParentWithChildren(String parentXpath, String... childNames) {
        ShapeNode parent = new ShapeNode(new QName(localPart(parentXpath)), parentXpath);
        for (String name : childNames) {
            ShapeNode child = new ShapeNode(new QName(name), parentXpath + "/" + name);
            parent.orderedContent().add(new ContentItem.ChildSlot(child));
        }
        return parent;
    }

    private String localPart(String xpath) {
        int last = xpath.lastIndexOf('/');
        return last >= 0 ? xpath.substring(last + 1) : xpath;
    }

    /** Creates a Signature from (localName, bucket) pairs. */
    private Signature sig(Object... nameAndBuckets) {
        List<Signature.Slot> slots = new ArrayList<>();
        for (int i = 0; i < nameAndBuckets.length; i += 2) {
            String name = (String) nameAndBuckets[i];
            int bucket = (Integer) nameAndBuckets[i + 1];
            slots.add(new Signature.Slot(new QName(name), bucket));
        }
        return new Signature(slots);
    }

    /** Builds an AnalysisContext with explicit signature map. */
    private AnalysisContext ctxWithSigs(Map<String, List<Signature>> sigsPerXpath) {
        return new AnalysisContext(InferenceConfig.defaults(), List.of(), Map.of(), sigsPerXpath);
    }

    private AnalysisContext ctxWithSigs(InferenceConfig cfg, Map<String, List<Signature>> sigsPerXpath) {
        return new AnalysisContext(cfg, List.of(), Map.of(), sigsPerXpath);
    }

    /** Repeats a signature n times into a list. */
    private List<Signature> repeat(Signature sig, int n) {
        List<Signature> result = new ArrayList<>();
        for (int i = 0; i < n; i++) result.add(sig);
        return result;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void emptySigsListIsNoOp() {
        ShapeNode parent = buildParentWithChildren("/entry", "a", "b");
        AnalysisContext ctx = ctxWithSigs(Map.of()); // no sigs for /entry

        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isEmpty();
    }

    @Test
    void singleSignatureIsNoOp() {
        ShapeNode parent = buildParentWithChildren("/entry", "sku", "qty");
        // All instances share the same signature
        Signature oneSig = sig("sku", 1, "qty", 1);
        List<Signature> sigs = repeat(oneSig, 10);

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isEmpty();
    }

    @Test
    void mutuallyDistinctSignaturesProduceTwoBranchChoose() {
        // /entry sometimes has (sku, qty), sometimes has (promo, discount)
        ShapeNode parent = buildParentWithChildren("/entry", "sku", "qty", "promo", "discount");

        Signature sigA = sig("sku", 1, "qty", 1);
        Signature sigB = sig("promo", 1, "discount", 1);

        // 7 instances with sigA, 3 with sigB
        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigA, 7));
        sigs.addAll(repeat(sigB, 3));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isPresent();
        Directive.Choose choose = (Directive.Choose) parent.directive().get();
        assertThat(choose.branches()).hasSize(2);

        // Branches have weights matching observation counts
        int totalWeight = choose.branches().stream().mapToInt(Directive.Branch::weight).sum();
        assertThat(totalWeight).isEqualTo(10);
        assertThat(choose.branches()).anyMatch(b -> b.weight() == 7);
        assertThat(choose.branches()).anyMatch(b -> b.weight() == 3);
    }

    @Test
    void subsetChainProducesChoose() {
        // Subset chain: sigSmall ⊂ sigLarge
        // sigSmall: (sku:1)
        // sigLarge: (sku:1, qty:1)
        ShapeNode parent = buildParentWithChildren("/entry", "sku", "qty");

        Signature sigSmall = sig("sku", 1);
        Signature sigLarge = sig("sku", 1, "qty", 1);

        // 4 small, 6 large
        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigSmall, 4));
        sigs.addAll(repeat(sigLarge, 6));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        // v1: subset-chain emits a Choose (renderer refines in Task 19)
        assertThat(parent.directive()).isPresent();
        assertThat(parent.directive().get()).isInstanceOf(Directive.Choose.class);
        Directive.Choose choose = (Directive.Choose) parent.directive().get();
        assertThat(choose.branches()).hasSize(2);
        assertThat(choose.branches()).anyMatch(b -> b.weight() == 4);
        assertThat(choose.branches()).anyMatch(b -> b.weight() == 6);
    }

    @Test
    void rareSignatureBelowThresholdIsDropped() {
        // 19 with sigA, 1 with sigRare (1/20 = 5% < 10% threshold)
        ShapeNode parent = buildParentWithChildren("/entry", "a", "b");
        Signature sigA = sig("a", 1);
        Signature sigRare = sig("b", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigA, 19));
        sigs.addAll(repeat(sigRare, 1));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        // After dropping sigRare, only 1 sig remains → no choose
        assertThat(parent.directive()).isEmpty();

        // DroppedRareSignature warning must have been emitted
        assertThat(ctx.warnings())
                .hasSize(1)
                .allMatch(w -> w instanceof InferenceWarning.DroppedRareSignature);
        InferenceWarning.DroppedRareSignature dropped =
                (InferenceWarning.DroppedRareSignature) ctx.warnings().get(0);
        assertThat(dropped.count()).isEqualTo(1);
        assertThat(dropped.totalObservations()).isEqualTo(20);
    }

    @Test
    void rareSignatureDroppedButRemainingTwoDistinctStillChoose() {
        // 18 with sigA, 11 with sigB, 1 with sigRare
        // After dropping sigRare: 2 distinct → Choose
        ShapeNode parent = buildParentWithChildren("/entry", "a", "b", "c");
        Signature sigA = sig("a", 1);
        Signature sigB = sig("b", 1);
        Signature sigRare = sig("c", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigA, 18));
        sigs.addAll(repeat(sigB, 11));
        sigs.addAll(repeat(sigRare, 1));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isPresent();
        Directive.Choose choose = (Directive.Choose) parent.directive().get();
        assertThat(choose.branches()).hasSize(2);

        assertThat(ctx.warnings())
                .hasSize(1)
                .allMatch(w -> w instanceof InferenceWarning.DroppedRareSignature);
    }

    @Test
    void moreThanMaxBranchesEmitsOverflowWarningAndCaps() {
        // 5 distinct signatures, each with >10% of 50 observations (10 each)
        // chooseMaxBranches = 4 → should cap at 4 and emit ChooseBranchOverflow
        ShapeNode parent = buildParentWithChildren("/entry", "a", "b", "c", "d", "e");

        Signature s1 = sig("a", 1);
        Signature s2 = sig("b", 1);
        Signature s3 = sig("c", 1);
        Signature s4 = sig("d", 1);
        Signature s5 = sig("e", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(s1, 10));
        sigs.addAll(repeat(s2, 10));
        sigs.addAll(repeat(s3, 10));
        sigs.addAll(repeat(s4, 10));
        sigs.addAll(repeat(s5, 10));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isPresent();
        Directive.Choose choose = (Directive.Choose) parent.directive().get();
        assertThat(choose.branches()).hasSize(4); // capped at chooseMaxBranches

        assertThat(ctx.warnings())
                .hasSize(1)
                .allMatch(w -> w instanceof InferenceWarning.ChooseBranchOverflow);
        InferenceWarning.ChooseBranchOverflow overflow =
                (InferenceWarning.ChooseBranchOverflow) ctx.warnings().get(0);
        assertThat(overflow.branchCount()).isEqualTo(5);
        assertThat(overflow.max()).isEqualTo(4);
    }

    @Test
    void branchBodyContainsChildrenFromSignature() {
        // sigA has only "sku"; sigB has only "qty"; parent has both children
        ShapeNode parent = buildParentWithChildren("/entry", "sku", "qty");
        Signature sigA = sig("sku", 1);
        Signature sigB = sig("qty", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigA, 5));
        sigs.addAll(repeat(sigB, 5));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isPresent();
        Directive.Choose choose = (Directive.Choose) parent.directive().get();

        // Each branch should contain exactly 1 child (either sku or qty)
        for (Directive.Branch branch : choose.branches()) {
            assertThat(branch.body()).hasSize(1);
            assertThat(branch.body().get(0)).isInstanceOf(ContentItem.ChildSlot.class);
        }

        // Together they cover both child names
        List<String> allBodyNames = choose.branches().stream()
                .flatMap(b -> b.body().stream())
                .filter(ci -> ci instanceof ContentItem.ChildSlot)
                .map(ci -> ((ContentItem.ChildSlot) ci).node().qName().getLocalPart())
                .toList();
        assertThat(allBodyNames).containsExactlyInAnyOrder("sku", "qty");
    }

    @Test
    void analyzerWalksChildrenRecursively() {
        // /root -> /root/parent, and /root/parent has the choose condition
        ShapeNode root = new ShapeNode(new QName("root"), "/root");
        ShapeNode parent = buildParentWithChildren("/root/parent", "a", "b");
        root.orderedContent().add(new ContentItem.ChildSlot(parent));

        Signature sigA = sig("a", 1);
        Signature sigB = sig("b", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigA, 5));
        sigs.addAll(repeat(sigB, 5));

        // Sigs are keyed under /root/parent, not /root
        AnalysisContext ctx = ctxWithSigs(Map.of("/root/parent", sigs));
        new ChooseAnalyzer().analyze(root, ctx);

        // Root should have no directive
        assertThat(root.directive()).isEmpty();
        // Parent (reached by recursive walk) should have Choose
        assertThat(parent.directive()).isPresent();
        assertThat(parent.directive().get()).isInstanceOf(Directive.Choose.class);
    }

    @Test
    void threeWaySubsetChainProducesChoose() {
        // sig1: (sku:1)
        // sig2: (sku:1, qty:1)
        // sig3: (sku:1, qty:1, discount:1)
        // sig1 ⊂ sig2 ⊂ sig3 → subset chain
        ShapeNode parent = buildParentWithChildren("/entry", "sku", "qty", "discount");

        Signature sig1 = sig("sku", 1);
        Signature sig2 = sig("sku", 1, "qty", 1);
        Signature sig3 = sig("sku", 1, "qty", 1, "discount", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sig1, 3));
        sigs.addAll(repeat(sig2, 5));
        sigs.addAll(repeat(sig3, 2));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isPresent();
        Directive.Choose choose = (Directive.Choose) parent.directive().get();
        assertThat(choose.branches()).hasSize(3);
    }

    @Test
    void nonSubsetDistinctGroupsProduceMultiBranchChoose() {
        // sigA and sigB share "common" but diverge on the extra element
        // sigA: (common:1, extra_a:1), sigB: (common:1, extra_b:1)
        // These are NOT subset of each other → mutually distinct
        ShapeNode parent = buildParentWithChildren("/entry", "common", "extra_a", "extra_b");

        Signature sigA = sig("common", 1, "extra_a", 1);
        Signature sigB = sig("common", 1, "extra_b", 1);

        List<Signature> sigs = new ArrayList<>();
        sigs.addAll(repeat(sigA, 6));
        sigs.addAll(repeat(sigB, 4));

        AnalysisContext ctx = ctxWithSigs(Map.of("/entry", sigs));
        new ChooseAnalyzer().analyze(parent, ctx);

        assertThat(parent.directive()).isPresent();
        Directive.Choose choose = (Directive.Choose) parent.directive().get();
        assertThat(choose.branches()).hasSize(2);
    }
}
