package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.GenNs;
import org.brylex.xmlgen.Pools;

import javax.xml.namespace.QName;
import java.util.*;

/**
 * Walks a decorated {@link ShapeNode} tree and produces an annotated XML
 * template string together with the {@link Pools} required to expand it.
 *
 * <p>Use {@link #render(ShapeNode)} to obtain a {@link Result} record
 * containing both the XML string and the pools.
 */
public class TemplateRenderer {

    public record Result(String xml, Pools pools) {}

    public Result render(ShapeNode root) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<Map<String, String>>> pendingPoolRows = new LinkedHashMap<>();
        // Pools whose rows came pre-sorted from PickCoherenceAnalyzer — skip redundant re-sort.
        Set<String> preSortedPools = new HashSet<>();

        renderNode(root, sb, 0, true, pendingPoolRows, preSortedPools);

        Pools.Builder poolsBuilder = Pools.builder();
        for (Map.Entry<String, List<Map<String, String>>> e : pendingPoolRows.entrySet()) {
            List<Map<String, String>> rows = new ArrayList<>(e.getValue());
            if (!preSortedPools.contains(e.getKey())) {
                rows.sort(Comparator.comparing(m -> {
                    Iterator<String> it = m.values().iterator();
                    return it.hasNext() ? it.next() : "";
                }));
            }
            poolsBuilder.inline(e.getKey(), rows);
        }

        return new Result(sb.toString(), poolsBuilder.build());
    }

    private void renderNode(ShapeNode node, StringBuilder sb, int indent, boolean isRoot,
                            Map<String, List<Map<String, String>>> pendingPoolRows,
                            Set<String> preSortedPools) {

        // Mixed-content path: emit content in document order without pretty-print re-indenting
        if (node.hasMixedContent()) {
            renderMixedContent(node, sb, indent, isRoot, pendingPoolRows, preSortedPools);
            return;
        }

        Optional<Directive> directiveOpt = node.directive();

        // Choose directive: render as <gen:choose> wrapper, not an attribute
        if (directiveOpt.isPresent() && directiveOpt.get() instanceof Directive.Choose choose) {
            renderChoose(node, choose, sb, indent, isRoot, pendingPoolRows, preSortedPools);
            return;
        }

        // WARNING comment for Pick with fully-unique values (emitted before the opening tag)
        if (directiveOpt.isPresent() && directiveOpt.get() instanceof Directive.Pick) {
            ShapeNode.ValueSamples vs = node.valueSamples();
            if (!vs.isEmpty() && vs.distinctCount() == vs.total()) {
                indent(sb, indent);
                sb.append("<!-- WARNING: every observed value at ").append(node.xpath())
                  .append(" was unique; pool will cycle on expansion. -->\n");
            }
        }

        indent(sb, indent);
        appendOpenTag(sb, node, isRoot);

        // Directive as XML attribute
        directiveOpt.ifPresent(d -> writeDirective(sb, d, node, pendingPoolRows, preSortedPools));

        boolean hasChildren = node.orderedContent().stream()
                .anyMatch(ci -> ci instanceof ContentItem.ChildSlot);
        boolean hasValueSamples = !node.valueSamples().isEmpty();

        if (!hasChildren && !hasValueSamples) {
            sb.append("/>\n");
            return;
        }

        sb.append(">");

        if (hasChildren) {
            sb.append("\n");
            for (ContentItem ci : node.orderedContent()) {
                renderContentItem(ci, sb, indent + 2, pendingPoolRows, preSortedPools);
            }
            indent(sb, indent);
        } else {
            // Leaf element
            if (directiveOpt.isPresent()) {
                sb.append("_");
            } else {
                // Pick the lexicographically-smallest observed value for deterministic output
                String literalValue = node.valueSamples().distinct().stream()
                        .min(Comparator.naturalOrder())
                        .orElse("");
                sb.append(escape(literalValue));
            }
        }

        sb.append("</").append(qNameToString(node.qName())).append(">\n");
    }

    /**
     * Renders a mixed-content element: text and child elements are emitted in
     * document order without extra newlines between siblings, preserving the
     * original inline layout.
     */
    private void renderMixedContent(ShapeNode node, StringBuilder sb, int indent, boolean isRoot,
                                    Map<String, List<Map<String, String>>> pendingPoolRows,
                                    Set<String> preSortedPools) {
        indent(sb, indent);
        appendOpenTag(sb, node, isRoot);
        sb.append(">");

        for (ContentItem ci : node.orderedContent()) {
            switch (ci) {
                case ContentItem.Text t -> sb.append(escape(t.value()));
                case ContentItem.ChildSlot cs -> {
                    // Inline child: render at indent 0, then strip trailing newline
                    StringBuilder childSb = new StringBuilder();
                    renderNode(cs.node(), childSb, 0, false, pendingPoolRows, preSortedPools);
                    String childStr = childSb.toString();
                    if (childStr.endsWith("\n")) {
                        childStr = childStr.substring(0, childStr.length() - 1);
                    }
                    sb.append(childStr);
                }
                case ContentItem.Cdata cdata -> sb.append("<![CDATA[").append(cdata.value()).append("]]>");
                case ContentItem.Comment comment -> sb.append("<!--").append(comment.value()).append("-->");
                case ContentItem.ProcessingInstruction pi ->
                        sb.append("<?").append(pi.target()).append(" ").append(pi.data()).append("?>");
            }
        }

        sb.append("</").append(qNameToString(node.qName())).append(">\n");
    }

    /**
     * Renders a Choose directive as a {@code <gen:choose>} block with weighted
     * {@code <gen:when>} branches inside the element body.
     */
    private void renderChoose(ShapeNode node, Directive.Choose choose, StringBuilder sb,
                               int indent, boolean isRoot,
                               Map<String, List<Map<String, String>>> pendingPoolRows,
                               Set<String> preSortedPools) {
        indent(sb, indent);
        appendOpenTag(sb, node, isRoot);
        sb.append(">\n");

        indent(sb, indent + 2);
        sb.append("<gen:choose>\n");

        for (Directive.Branch branch : choose.branches()) {
            indent(sb, indent + 4);
            sb.append("<gen:when weight=\"").append(branch.weight()).append("\">\n");
            for (ContentItem ci : branch.body()) {
                renderContentItem(ci, sb, indent + 6, pendingPoolRows, preSortedPools);
            }
            indent(sb, indent + 4);
            sb.append("</gen:when>\n");
        }

        indent(sb, indent + 2);
        sb.append("</gen:choose>\n");

        indent(sb, indent);
        sb.append("</").append(qNameToString(node.qName())).append(">\n");
    }

    private void renderContentItem(ContentItem ci, StringBuilder sb, int indent,
                                   Map<String, List<Map<String, String>>> pendingPoolRows,
                                   Set<String> preSortedPools) {
        switch (ci) {
            case ContentItem.ChildSlot cs -> renderNode(cs.node(), sb, indent, false, pendingPoolRows, preSortedPools);
            case ContentItem.Text t -> { indent(sb, indent); sb.append(escape(t.value())).append("\n"); }
            case ContentItem.Cdata cdata -> {
                indent(sb, indent);
                sb.append("<![CDATA[").append(cdata.value()).append("]]>\n");
            }
            case ContentItem.Comment comment -> {
                indent(sb, indent);
                sb.append("<!--").append(comment.value()).append("-->\n");
            }
            case ContentItem.ProcessingInstruction pi -> {
                indent(sb, indent);
                sb.append("<?").append(pi.target()).append(" ").append(pi.data()).append("?>\n");
            }
        }
    }

    /**
     * Appends {@code <qName [xmlns:gen="..."] [attrs]>} to {@code sb}, without the
     * closing {@code >} so callers can append additional content (directive attrs, body
     * opener) before closing.
     */
    private void appendOpenTag(StringBuilder sb, ShapeNode node, boolean isRoot) {
        sb.append("<").append(qNameToString(node.qName()));
        if (isRoot) sb.append(" ").append(GenNs.XMLNS_DECL);
        for (Map.Entry<QName, ShapeNode.ValueSamples> e : node.attributes().entrySet()) {
            String value = e.getValue().counts().keySet().stream()
                    .min(Comparator.naturalOrder()).orElse("");
            sb.append(" ").append(qNameToString(e.getKey()))
              .append("=\"").append(escape(value)).append("\"");
        }
    }

    private void writeDirective(StringBuilder sb, Directive d, ShapeNode node,
                                Map<String, List<Map<String, String>>> pendingPoolRows,
                                Set<String> preSortedPools) {
        switch (d) {
            case Directive.Repeat r -> {
                if (r.isFixed()) {
                    sb.append(" gen:repeat=\"").append(r.min()).append("\"");
                } else {
                    sb.append(" gen:repeat=\"").append(r.min()).append("..").append(r.max()).append("\"");
                }
            }
            case Directive.Increment i -> sb.append(" gen:increment=\"").append(i.step()).append("\"");
            case Directive.Random rnd -> writeRandom(sb, rnd.range());
            case Directive.Pick p -> {
                sb.append(" gen:pick=\"").append(p.poolName()).append("/").append(p.column()).append("\"");
                if (p.poolRows() != null) {
                    // Combined multi-column rows provided by PickCoherenceAnalyzer — already
                    // sorted lexicographically; register and mark as pre-sorted.
                    pendingPoolRows.put(p.poolName(), new ArrayList<>(p.poolRows()));
                    preSortedPools.add(p.poolName());
                } else if (!pendingPoolRows.containsKey(p.poolName())) {
                    // No combined rows and pool not yet registered — fall back to single-column
                    // rows derived from the leaf's own value samples (legacy / unit-test path).
                    for (String v : node.valueSamples().distinct()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put(p.column(), v);
                        pendingPoolRows.computeIfAbsent(p.poolName(), k -> new ArrayList<>()).add(row);
                    }
                }
                // If pool already registered (subsequent leaves in a bijective group) — skip.
            }
            case Directive.Choose c -> throw new IllegalStateException(
                    "Choose directive should be dispatched before writeDirective is called");
        }
    }

    private void writeRandom(StringBuilder sb, Range range) {
        switch (range) {
            case Range.IntRange r -> sb.append(" gen:random-int=\"").append(r.min()).append("..").append(r.max()).append("\"");
            case Range.AmountRange r -> sb.append(" gen:random-amount=\"")
                    .append(r.min().toPlainString()).append("..").append(r.max().toPlainString()).append("\"");
            case Range.DateRange r -> sb.append(" gen:random-date=\"").append(r.min()).append("..").append(r.max()).append("\"");
            case Range.Uuid u -> sb.append(" gen:random-uuid=\"true\"");
        }
    }

    private void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append(' ');
    }

    private String qNameToString(QName q) {
        if (q.getPrefix() == null || q.getPrefix().isEmpty()) return q.getLocalPart();
        return q.getPrefix() + ":" + q.getLocalPart();
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
