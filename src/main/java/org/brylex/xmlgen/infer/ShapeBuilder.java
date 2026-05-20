package org.brylex.xmlgen.infer;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
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
     */
    public ShapeNode build() {
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
        ShapeNode merged = new ShapeNode(rootName, "/" + rootName.getLocalPart());
        for (ShapeNode r : sampleRoots) {
            mergeInto(merged, r);
        }
        return merged;
    }

    // -------------------------------------------------------------------------
    // Walking
    // -------------------------------------------------------------------------

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

                // Reject gen: namespace elements
                if (GEN_NS.equals(name.getNamespaceURI())) {
                    throw new InferenceException(
                            "sample " + sampleIndex + " contains gen: element " + name + " at depth " + depth);
                }

                ShapeNode parent = nodeStack.peek();
                String xpath = (parent == null)
                        ? "/" + name.getLocalPart()
                        : parent.xpath() + "/" + name.getLocalPart();

                ShapeNode node;
                if (parent == null) {
                    // Root element
                    node = new ShapeNode(name, xpath);
                    root = node;
                } else {
                    // Reuse existing ChildSlot for same qName, or create a new one
                    Map<QName, ShapeNode> childSlots = childSlotStack.peek();
                    Map<QName, Integer> childCounts = childCountStack.peek();

                    node = childSlots.get(name);
                    if (node == null) {
                        node = new ShapeNode(name, xpath);
                        childSlots.put(name, node);
                        parent.orderedContent().add(new ContentItem.ChildSlot(node));
                    }
                    childCounts.merge(name, 1, Integer::sum);
                }

                // Absorb attributes, rejecting gen: attributes
                absorbAttributes(node, se, sampleIndex);

                nodeStack.push(node);
                childCountStack.push(new LinkedHashMap<>());
                childSlotStack.push(new LinkedHashMap<>());

            } else if (event.isEndElement()) {
                ShapeNode finishing = nodeStack.pop();
                Map<QName, Integer> childCounts = childCountStack.pop();
                childSlotStack.pop();

                // Record how many times each child qName appeared under this instance
                for (Map.Entry<QName, Integer> entry : childCounts.entrySet()) {
                    Map<QName, ShapeNode> parentSlots = childSlotStack.isEmpty()
                            ? Map.of()
                            : childSlotStack.peek();
                    // The child node is already keyed in the parent's childSlotStack;
                    // but we already popped it. Look up in finishing's orderedContent instead.
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

                depth--;

            } else if (event.isCharacters()) {
                Characters chars = event.asCharacters();
                if (!nodeStack.isEmpty() && !chars.isWhiteSpace() && !chars.isIgnorableWhiteSpace()) {
                    nodeStack.peek().valueSamples().add(chars.getData());
                }
            }
            // Other event types (PI, Comment, CDATA) deferred to Task 11
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
}
