package org.brylex.xmlgen.infer;

import javax.xml.namespace.QName;
import java.util.*;

public final class ShapeNode {

    private final QName qName;
    private final String xpath;
    private final List<ContentItem> orderedContent = new ArrayList<>();
    private final ValueSamples valueSamples = new ValueSamples();
    private final IntSummaryStatistics cardinalityPerParent = new IntSummaryStatistics();
    private final Map<QName, ValueSamples> attributes = new LinkedHashMap<>();
    private boolean hasMixedContent;
    private Directive directive;

    public ShapeNode(QName qName, String xpath) {
        this.qName = qName;
        this.xpath = xpath;
    }

    public QName qName() { return qName; }
    public String xpath() { return xpath; }
    public List<ContentItem> orderedContent() { return orderedContent; }
    public ValueSamples valueSamples() { return valueSamples; }
    public IntSummaryStatistics cardinalityPerParent() { return cardinalityPerParent; }
    public Map<QName, ValueSamples> attributes() { return attributes; }

    public boolean hasMixedContent() { return hasMixedContent; }
    public void markMixedContent() { this.hasMixedContent = true; }

    public Optional<Directive> directive() { return Optional.ofNullable(directive); }

    public boolean isLeaf() {
        return orderedContent.stream().noneMatch(ci -> ci instanceof ContentItem.ChildSlot);
    }

    public void setDirective(Directive directive) {
        if (this.directive != null) {
            throw new IllegalStateException(
                    "Directive already set on " + xpath + ": " + this.directive);
        }
        this.directive = directive;
    }

    /** Multiset used for value-sample tracking with optional truncation accounting. */
    public static final class ValueSamples {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private int total;
        private int truncatedSince;

        public void add(String value) {
            counts.merge(value, 1, Integer::sum);
            total++;
        }
        public boolean isEmpty() { return total == 0; }
        public int total() { return total; }
        public Map<String, Integer> counts() { return Collections.unmodifiableMap(counts); }
        public Set<String> distinct() { return counts.keySet(); }
        public int distinctCount() { return counts.size(); }
        public void recordTruncation(int dropped) { this.truncatedSince += dropped; }
        public int truncatedCount() { return truncatedSince; }
    }
}
