package org.brylex.xmlgen.infer;

import javax.xml.namespace.QName;
import java.util.List;
import java.util.Objects;

/**
 * Ordered sequence of (qName, cardinality-bucket) pairs representing the observed
 * child structure of one parent-instance in a sample. Used by {@link analyzers.ChooseAnalyzer}
 * to group parent-instances into structural signature groups.
 *
 * <p>Cardinality buckets: 0 = not present (used in subset-chain padding), 1 = exactly one,
 * 2 = two or more.
 */
public record Signature(List<Slot> slots) {

    public Signature {
        slots = List.copyOf(slots);
    }

    public record Slot(QName qName, int cardinalityBucket) {
        public Slot {
            if (cardinalityBucket < 0 || cardinalityBucket > 2) {
                throw new IllegalArgumentException(
                        "cardinalityBucket must be 0, 1 or 2; got " + cardinalityBucket);
            }
        }
    }

    /**
     * Returns a human-readable representation like {@code (sku:1,qty:1)}.
     */
    public String repr() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(slots.get(i).qName().getLocalPart())
              .append(":")
              .append(slots.get(i).cardinalityBucket());
        }
        return sb.append(")").toString();
    }

    /**
     * Returns, from the parent's ordered content, only the {@link ContentItem.ChildSlot}
     * entries whose qName appears in this signature (in original orderedContent order).
     *
     * <p>Elements with a cardinality bucket of 0 are excluded (they are absent in this signature).
     */
    public List<ContentItem> bodyFor(ShapeNode parent) {
        return parent.orderedContent().stream()
                .filter(ci -> {
                    if (!(ci instanceof ContentItem.ChildSlot cs)) return false;
                    QName cn = cs.node().qName();
                    return slots.stream().anyMatch(
                            slot -> slot.cardinalityBucket() > 0 && Objects.equals(slot.qName(), cn));
                })
                .toList();
    }
}
