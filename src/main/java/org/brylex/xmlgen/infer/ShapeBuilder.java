package org.brylex.xmlgen.infer;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.Comment;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.ProcessingInstruction;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.*;

/**
 * Walks one or more XMLEventReader samples into per-sample ShapeNode trees,
 * then merges them into a single union tree.
 *
 * <p>Not thread-safe.
 */
public class ShapeBuilder {

    private static final String GEN_NS = "urn:xml:gen";

    private final InferenceConfig config;
    private final List<ShapeNode> sampleRoots = new ArrayList<>();
    /**
     * Accumulates observed child-qName orderings per xpath across all samples.
     * Key: parent xpath. Value: list of ordered child-qName sequences observed
     * in individual parent instances.
     */
    private final Map<String, List<List<QName>>> observedSequences = new LinkedHashMap<>();
    private final List<InferenceWarning> warnings = new ArrayList<>();

    /**
     * iterationValues: leafXpath → list of per-parent-instance value sequences.
     * Each inner list is the ordered values of that leaf within one parent-instance
     * that has ≥ 2 same-qName leaf occurrences (i.e. the leaf repeats under that parent).
     */
    private final Map<String, List<List<String>>> iterationValues = new LinkedHashMap<>();

    /**
     * signatures: parentXpath → list of child-signatures (one per parent-instance observed).
     */
    private final Map<String, List<Signature>> signatures = new LinkedHashMap<>();

    /**
     * rows: parentXpath → list of leaf-value tuples (one per parent-instance observed).
     * Each tuple maps leaf local-name → first-observed text value within that parent-instance.
     */
    private final Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();

    /**
     * Cached result of {@link #build()} — null until build() is called.
     */
    private ShapeNode mergedRoot = null;

    public ShapeBuilder(InferenceConfig config) {
        this.config = config;
    }

    /**
     * Walk one XML sample and accumulate its structure.
     */
    public void absorb(XMLEventReader reader) throws XMLStreamException {
        ShapeNode root = walk(reader, sampleRoots.size() + 1);
        sampleRoots.add(root);
    }

    /**
     * Produce a merged ShapeNode tree from all absorbed samples.
     * Fails loud if no samples were absorbed, or if sample roots disagree on element name.
     * Idempotent: subsequent calls return the cached result.
     */
    public ShapeNode build() {
        if (mergedRoot != null) {
            return mergedRoot;
        }
        if (sampleRoots.isEmpty()) {
            throw new InferenceException("at least one sample required");
        }
        QName rootName = sampleRoots.get(0).qName();
        for (int i = 1; i < sampleRoots.size(); i++) {
            QName otherName = sampleRoots.get(i).qName();
            if (!rootName.equals(otherName)) {
                throw new InferenceException(
                        "samples disagree on root element: "
                                + rootName.getLocalPart() + " vs " + otherName.getLocalPart());
            }
        }
        // Check mixed-content divergence across all pairs of per-sample trees
        if (sampleRoots.size() > 1) {
            checkMixedContentConsistency(sampleRoots);
        }
        ShapeNode merged = new ShapeNode(rootName, "/" + rootName.getLocalPart());
        for (ShapeNode r : sampleRoots) {
            mergeInto(merged, r);
        }
        // Reorder children of every node in the merged tree according to canonical sequences
        applyCanonicalOrder(merged);
        mergedRoot = merged;
        return mergedRoot;
    }

    /**
     * Returns combined observation data from all absorbed samples. Calls {@link #build()}
     * if not already called.
     */
    public ObservationData observationData() {
        if (mergedRoot == null) {
            build();
        }
        return new ObservationData(mergedRoot, warnings, iterationValues, signatures, rows);
    }

    /**
     * Cross-check all pairs of per-sample trees for mixed-content consistency.
     * Uses the first sample as the reference and verifies each subsequent sample matches
     * its hasMixedContent at every xpath that appears in both.
     */
    private void checkMixedContentConsistency(List<ShapeNode> roots) {
        // Build a flat map of xpath -> hasMixedContent from the first sample
        Map<String, Boolean> referenceFlags = new LinkedHashMap<>();
        collectMixedContentFlags(roots.get(0), referenceFlags);

        for (int i = 1; i < roots.size(); i++) {
            Map<String, Boolean> currentFlags = new LinkedHashMap<>();
            collectMixedContentFlags(roots.get(i), currentFlags);
            for (Map.Entry<String, Boolean> ref : referenceFlags.entrySet()) {
                Boolean current = currentFlags.get(ref.getKey());
                if (current != null && !current.equals(ref.getValue())) {
                    throw new InferenceException(
                            "mixed-content divergence at " + ref.getKey()
                                    + ": samples disagree on whether content is mixed");
                }
            }
            // Also check xpaths in current that exist in reference
            for (Map.Entry<String, Boolean> cur : currentFlags.entrySet()) {
                Boolean ref = referenceFlags.get(cur.getKey());
                if (ref != null && !ref.equals(cur.getValue())) {
                    throw new InferenceException(
                            "mixed-content divergence at " + cur.getKey()
                                    + ": samples disagree on whether content is mixed");
                }
            }
        }
    }

    private void collectMixedContentFlags(ShapeNode node, Map<String, Boolean> result) {
        result.put(node.xpath(), node.hasMixedContent());
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                collectMixedContentFlags(cs.node(), result);
            }
        }
    }

    /**
     * Returns warnings accumulated during {@link #build()} — e.g. ambiguous child ordering.
     * Will be consumed by AnalysisContext in Task 12+.
     */
    public List<InferenceWarning> warnings() {
        return List.copyOf(warnings);
    }

    // -------------------------------------------------------------------------
    // Walking
    // -------------------------------------------------------------------------

    /** Per-element state used during walk() to detect mixed content. */
    private static class ElementState {
        boolean hasNonWhitespaceText;
        boolean hasChildElement;
    }

    /**
     * Per-parent-instance observation state tracked on a parallel deque during walk().
     * Populated for each element instance to collect:
     * <ul>
     *   <li>ownTextValues: text values belonging directly to this element (leaf text)</li>
     *   <li>childValueLists: leafLocalName → ordered list of text values seen for that leaf
     *       under this parent-instance (may contain multiple entries for repeated leaves)</li>
     *   <li>childCounts: qName → count of times that child appeared</li>
     *   <li>firstLeafValues: leafLocalName → first observed text value (for rows)</li>
     * </ul>
     */
    private static class ParentInstanceObservation {
        /** Text values accumulated directly in this element (for leaf text passing to parent). */
        final List<String> ownTextValues = new ArrayList<>();
        /** Maps leaf local-name → ordered list of text values within this parent-instance. */
        final Map<String, List<String>> childValueLists = new LinkedHashMap<>();
        /** Maps child qName → occurrence count within this parent-instance. */
        final Map<QName, Integer> childQNameCounts = new LinkedHashMap<>();
        /** Maps leaf local-name → first text value observed (for rows map). */
        final Map<String, String> firstLeafValues = new LinkedHashMap<>();

        /** Called when this element has finished as a leaf, notifying the parent with all its text. */
        void receiveLeafText(String localName, List<String> texts) {
            for (String text : texts) {
                childValueLists.computeIfAbsent(localName, k -> new ArrayList<>()).add(text);
            }
            if (!texts.isEmpty()) {
                firstLeafValues.putIfAbsent(localName, texts.get(0));
            }
        }

        /** Called when we see a child start element. */
        void recordChildStart(QName qName) {
            childQNameCounts.merge(qName, 1, Integer::sum);
        }

        /** Build the Signature from observed child counts, in the order first encountered. */
        Signature buildSignature() {
            List<Signature.Slot> slots = new ArrayList<>();
            for (Map.Entry<QName, Integer> e : childQNameCounts.entrySet()) {
                int bucket = e.getValue() >= 2 ? 2 : 1;
                slots.add(new Signature.Slot(e.getKey(), bucket));
            }
            return new Signature(slots);
        }
    }

    /**
     * Walk a single sample, producing a ShapeNode tree where same-qName siblings
     * under the same parent are collapsed into a single ChildSlot with cardinality
     * recorded via IntSummaryStatistics.accept().
     */
    private ShapeNode walk(XMLEventReader reader, int sampleIndex) throws XMLStreamException {
        // Stack of in-progress nodes
        Deque<ShapeNode> nodeStack = new ArrayDeque<>();
        // Stack of "child occurrence counts per qName" for the current parent
        Deque<Map<QName, Integer>> childCountStack = new ArrayDeque<>();
        // Stack of "already-seen child ShapeNodes by qName" for the current parent
        Deque<Map<QName, ShapeNode>> childSlotStack = new ArrayDeque<>();
        // Stack tracking ordered child-qName sequence (deduplicated) for each parent instance
        Deque<List<QName>> childOrderStack = new ArrayDeque<>();
        // Stack tracking per-element mixed-content detection state
        Deque<ElementState> stateStack = new ArrayDeque<>();
        // Parallel stack of per-parent-instance observation state (one per open element)
        Deque<ParentInstanceObservation> observationStack = new ArrayDeque<>();

        ShapeNode root = null;
        int depth = 0;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                depth++;
                if (depth > config.maxSampleDepth()) {
                    throw new InferenceException(
                            "sample " + sampleIndex + " exceeds maxSampleDepth=" + config.maxSampleDepth());
                }

                StartElement se = event.asStartElement();
                QName name = se.getName();

                // Check for xmlns:gen with a conflicting URI on any element
                @SuppressWarnings("unchecked")
                Iterator<Namespace> nsIt = se.getNamespaces();
                while (nsIt.hasNext()) {
                    Namespace ns = nsIt.next();
                    if ("gen".equals(ns.getPrefix()) && !GEN_NS.equals(ns.getNamespaceURI())) {
                        throw new InferenceException(
                                "sample " + sampleIndex + " declares xmlns:gen with conflicting URI: "
                                        + ns.getNamespaceURI() + " (expected " + GEN_NS + ")");
                    }
                }

                // Reject gen: namespace elements
                if (GEN_NS.equals(name.getNamespaceURI())) {
                    throw new InferenceException(
                            "sample " + sampleIndex + " contains gen: element " + name + " at depth " + depth);
                }

                ShapeNode parent = nodeStack.peek();
                String xpath = (parent == null)
                        ? "/" + name.getLocalPart()
                        : parent.xpath() + "/" + name.getLocalPart();

                // Mark parent as having a child element (for mixed-content detection)
                if (!stateStack.isEmpty()) {
                    stateStack.peek().hasChildElement = true;
                }

                // Notify parent's observation about this child start
                if (!observationStack.isEmpty()) {
                    observationStack.peek().recordChildStart(name);
                }

                ShapeNode node;
                if (parent == null) {
                    // Root element
                    node = new ShapeNode(name, xpath);
                    root = node;
                } else {
                    // Reuse existing ChildSlot for same qName, or create a new one
                    Map<QName, ShapeNode> childSlots = childSlotStack.peek();
                    Map<QName, Integer> childCounts = childCountStack.peek();
                    List<QName> childOrder = childOrderStack.peek();

                    node = childSlots.get(name);
                    if (node == null) {
                        node = new ShapeNode(name, xpath);
                        childSlots.put(name, node);
                        parent.orderedContent().add(new ContentItem.ChildSlot(node));
                        // First time we see this child qName in this parent instance
                        childOrder.add(name);
                    }
                    childCounts.merge(name, 1, Integer::sum);
                }

                // Absorb attributes, rejecting gen: attributes
                absorbAttributes(node, se, sampleIndex);

                nodeStack.push(node);
                childCountStack.push(new LinkedHashMap<>());
                childSlotStack.push(new LinkedHashMap<>());
                childOrderStack.push(new ArrayList<>());
                stateStack.push(new ElementState());
                observationStack.push(new ParentInstanceObservation());

            } else if (event.isEndElement()) {
                ShapeNode finishing = nodeStack.pop();
                Map<QName, Integer> childCounts = childCountStack.pop();
                childSlotStack.pop();
                List<QName> childOrder = childOrderStack.pop();
                ElementState state = stateStack.pop();
                ParentInstanceObservation obs = observationStack.pop();

                // Mark mixed content if both significant text and child elements were seen
                if (state.hasNonWhitespaceText && state.hasChildElement) {
                    finishing.markMixedContent();
                }

                // Record the observed child order sequence for this xpath
                if (!childOrder.isEmpty()) {
                    observedSequences
                            .computeIfAbsent(finishing.xpath(), k -> new ArrayList<>())
                            .add(List.copyOf(childOrder));
                }

                // Record how many times each child qName appeared under this instance
                for (Map.Entry<QName, Integer> entry : childCounts.entrySet()) {
                    ShapeNode childNode = finishing.orderedContent().stream()
                            .filter(ci -> ci instanceof ContentItem.ChildSlot cs
                                    && cs.node().qName().equals(entry.getKey()))
                            .map(ci -> ((ContentItem.ChildSlot) ci).node())
                            .findFirst()
                            .orElse(null);
                    if (childNode != null) {
                        childNode.cardinalityPerParent().accept(entry.getValue());
                    }
                }

                // Pass leaf text up to the parent observation (if this element had text and no child elements)
                boolean isLeafElement = !state.hasChildElement && !obs.ownTextValues.isEmpty();
                if (isLeafElement && !observationStack.isEmpty()) {
                    observationStack.peek().receiveLeafText(
                            finishing.qName().getLocalPart(), obs.ownTextValues);
                }

                // Flush observation data into the three maps
                // --- signatures ---
                if (!obs.childQNameCounts.isEmpty()) {
                    signatures.computeIfAbsent(finishing.xpath(), k -> new ArrayList<>())
                            .add(obs.buildSignature());
                }

                // --- rows (leaf-value tuples per parent-instance) ---
                if (!obs.firstLeafValues.isEmpty()) {
                    rows.computeIfAbsent(finishing.xpath(), k -> new ArrayList<>())
                            .add(new LinkedHashMap<>(obs.firstLeafValues));
                }

                // --- iterationValues (repeated-leaf value sequences) ---
                for (Map.Entry<String, List<String>> e : obs.childValueLists.entrySet()) {
                    if (e.getValue().size() >= 2) {
                        String leafXpath = finishing.xpath() + "/" + e.getKey();
                        iterationValues.computeIfAbsent(leafXpath, k -> new ArrayList<>())
                                .add(List.copyOf(e.getValue()));
                    }
                }

                depth--;

            } else if (event.isCharacters()) {
                Characters chars = event.asCharacters();
                if (chars.isCData()) {
                    // CDATA counts as significant text for mixed-content detection
                    if (!stateStack.isEmpty()) {
                        stateStack.peek().hasNonWhitespaceText = true;
                    }
                    if (!nodeStack.isEmpty()) {
                        nodeStack.peek().orderedContent().add(new ContentItem.Cdata(chars.getData()));
                    }
                } else if (!chars.isWhiteSpace() && !chars.isIgnorableWhiteSpace()) {
                    // Regular significant text
                    if (!stateStack.isEmpty()) {
                        stateStack.peek().hasNonWhitespaceText = true;
                    }
                    if (!nodeStack.isEmpty()) {
                        nodeStack.peek().valueSamples().add(chars.getData());
                    }
                    // Accumulate text in the current observation for later passing to parent
                    if (!observationStack.isEmpty()) {
                        observationStack.peek().ownTextValues.add(chars.getData());
                    }
                }
            } else if (event.isProcessingInstruction()) {
                ProcessingInstruction pi = (ProcessingInstruction) event;
                if (!nodeStack.isEmpty()) {
                    nodeStack.peek().orderedContent().add(
                            new ContentItem.ProcessingInstruction(pi.getTarget(), pi.getData()));
                }
            } else if (event instanceof Comment c) {
                if (!nodeStack.isEmpty()) {
                    nodeStack.peek().orderedContent().add(new ContentItem.Comment(c.getText()));
                }
            }
        }

        if (root == null) {
            throw new InferenceException("sample " + sampleIndex + " contained no elements");
        }
        return root;
    }

    private void absorbAttributes(ShapeNode node, StartElement se, int sampleIndex) {
        @SuppressWarnings("unchecked")
        Iterator<Attribute> it = se.getAttributes();
        while (it.hasNext()) {
            Attribute attr = it.next();
            QName qn = attr.getName();
            if (GEN_NS.equals(qn.getNamespaceURI())) {
                throw new InferenceException(
                        "sample " + sampleIndex + " contains gen: attribute " + qn
                                + " at " + node.xpath());
            }
            node.attributes()
                    .computeIfAbsent(qn, k -> new ShapeNode.ValueSamples())
                    .add(attr.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Merging
    // -------------------------------------------------------------------------

    /**
     * Merge src's structure into dest (both share the same qName/xpath position).
     * Accumulates value samples, attribute samples, cardinality observations, and
     * recursively merges children.
     */
    private void mergeInto(ShapeNode dest, ShapeNode src) {
        // Propagate mixed-content flag
        if (src.hasMixedContent()) {
            dest.markMixedContent();
        }

        // Merge text value samples
        for (Map.Entry<String, Integer> e : src.valueSamples().counts().entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                dest.valueSamples().add(e.getKey());
            }
        }

        // Merge attribute value samples
        for (Map.Entry<QName, ShapeNode.ValueSamples> e : src.attributes().entrySet()) {
            ShapeNode.ValueSamples destAttr = dest.attributes()
                    .computeIfAbsent(e.getKey(), k -> new ShapeNode.ValueSamples());
            for (Map.Entry<String, Integer> v : e.getValue().counts().entrySet()) {
                for (int i = 0; i < v.getValue(); i++) {
                    destAttr.add(v.getKey());
                }
            }
        }

        // Build a lookup of dest's existing children by qName
        Map<QName, ShapeNode> destChildren = new LinkedHashMap<>();
        for (ContentItem ci : dest.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                destChildren.put(cs.node().qName(), cs.node());
            }
        }

        // Propagate non-ChildSlot content items (CDATA, PI, Comment) from src to dest
        // if dest doesn't already have them (avoid duplicating across multiple samples).
        Set<ContentItem> existingNonSlotItems = new java.util.HashSet<>();
        for (ContentItem ci : dest.orderedContent()) {
            if (!(ci instanceof ContentItem.ChildSlot)) {
                existingNonSlotItems.add(ci);
            }
        }
        for (ContentItem ci : src.orderedContent()) {
            if (!(ci instanceof ContentItem.ChildSlot) && !existingNonSlotItems.contains(ci)) {
                dest.orderedContent().add(ci);
                existingNonSlotItems.add(ci);
            }
        }

        // Merge each child from src into dest
        for (ContentItem ci : src.orderedContent()) {
            if (!(ci instanceof ContentItem.ChildSlot cs)) {
                continue;
            }
            ShapeNode srcChild = cs.node();
            ShapeNode destChild = destChildren.get(srcChild.qName());

            if (destChild == null) {
                // New child qName not seen in dest yet — create a fresh node
                destChild = new ShapeNode(
                        srcChild.qName(),
                        dest.xpath() + "/" + srcChild.qName().getLocalPart());
                dest.orderedContent().add(new ContentItem.ChildSlot(destChild));
                destChildren.put(srcChild.qName(), destChild);
            }

            // Carry over cardinality observations from src.
            // IntSummaryStatistics doesn't expose individual observations, so we
            // re-accept min and max (preserving range fidelity for RepeatAnalyzer).
            IntSummaryStatistics srcCard = srcChild.cardinalityPerParent();
            if (srcCard.getCount() > 0) {
                destChild.cardinalityPerParent().accept((int) srcCard.getMin());
                if (srcCard.getMax() != srcCard.getMin()) {
                    destChild.cardinalityPerParent().accept((int) srcCard.getMax());
                }
            }

            mergeInto(destChild, srcChild);
        }
    }

    // -------------------------------------------------------------------------
    // Canonical ordering
    // -------------------------------------------------------------------------

    /**
     * Post-process the merged tree: reorder children of each node according to the
     * most-frequent observed child-qName sequence (canonical order). Ties are broken
     * lexicographically on qName local-part sequence. An AmbiguousOrder warning is
     * emitted when there is a frequency tie among non-subset alternatives.
     */
    private void applyCanonicalOrder(ShapeNode node) {
        List<List<QName>> sequences = observedSequences.get(node.xpath());
        if (sequences != null && !sequences.isEmpty()) {
            List<QName> canonical = pickCanonical(sequences, node.xpath(), warnings);
            reorderChildren(node, canonical);
        }
        // Recurse into children
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                applyCanonicalOrder(cs.node());
            }
        }
    }

    /**
     * Reorder the ChildSlot entries in {@code node.orderedContent()} according to
     * {@code canonical}. Children not mentioned in the canonical sequence (i.e. seen
     * only in some samples) are appended in their existing relative order.
     */
    private void reorderChildren(ShapeNode node, List<QName> canonical) {
        List<ContentItem> content = node.orderedContent();

        // Extract existing child slots by qName (preserving relative order for extras)
        Map<QName, ContentItem.ChildSlot> slotsByQName = new LinkedHashMap<>();
        List<ContentItem> nonChildItems = new ArrayList<>();
        for (ContentItem ci : content) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                slotsByQName.put(cs.node().qName(), cs);
            } else {
                nonChildItems.add(ci);
            }
        }

        content.clear();
        content.addAll(nonChildItems);

        // Add in canonical order first
        Set<QName> placed = new LinkedHashSet<>();
        for (QName qn : canonical) {
            ContentItem.ChildSlot slot = slotsByQName.get(qn);
            if (slot != null) {
                content.add(slot);
                placed.add(qn);
            }
        }
        // Append any children not covered by the canonical sequence
        for (Map.Entry<QName, ContentItem.ChildSlot> e : slotsByQName.entrySet()) {
            if (!placed.contains(e.getKey())) {
                content.add(e.getValue());
            }
        }
    }

    /**
     * Pick the canonical child sequence from a list of observed sequences.
     * Uses most-frequent sequence; ties broken by lexicographic order of local-part sequences.
     * Emits an AmbiguousOrder warning when a frequency tie exists.
     */
    private List<QName> pickCanonical(List<List<QName>> sequences, String xpath,
                                       List<InferenceWarning> warningsSink) {
        if (sequences.isEmpty()) return List.of();

        Map<List<QName>, Integer> counts = new LinkedHashMap<>();
        for (List<QName> s : sequences) {
            counts.merge(s, 1, Integer::sum);
        }

        List<Map.Entry<List<QName>, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            if (c != 0) return c;
            return compareSequences(a.getKey(), b.getKey());
        });

        Map.Entry<List<QName>, Integer> top = sorted.get(0);
        if (sorted.size() > 1 && sorted.get(1).getValue().equals(top.getValue())) {
            warningsSink.add(new InferenceWarning.AmbiguousOrder(
                    xpath, reprSeq(top.getKey()), reprSeq(sorted.get(1).getKey())));
        }
        return top.getKey();
    }

    private int compareSequences(List<QName> a, List<QName> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = a.get(i).getLocalPart().compareTo(b.get(i).getLocalPart());
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    private String reprSeq(List<QName> seq) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(seq.get(i).getLocalPart());
        }
        return sb.append(")").toString();
    }
}
