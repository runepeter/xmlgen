package org.brylex.xmlgen.infer;

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

        renderNode(root, sb, 0, true, pendingPoolRows);

        Pools.Builder poolsBuilder = Pools.builder();
        for (Map.Entry<String, List<Map<String, String>>> e : pendingPoolRows.entrySet()) {
            List<Map<String, String>> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparing(m -> {
                Iterator<String> it = m.values().iterator();
                return it.hasNext() ? it.next() : "";
            }));
            poolsBuilder.inline(e.getKey(), rows);
        }

        return new Result(sb.toString(), poolsBuilder.build());
    }

    private void renderNode(ShapeNode node, StringBuilder sb, int indent, boolean isRoot,
                            Map<String, List<Map<String, String>>> pendingPoolRows) {

        Optional<Directive> directiveOpt = node.directive();

        indent(sb, indent);
        sb.append("<").append(qNameToString(node.qName()));

        if (isRoot) {
            sb.append(" xmlns:gen=\"urn:xml:gen\"");
        }

        // Plain attributes — first observed value only
        for (Map.Entry<QName, ShapeNode.ValueSamples> e : node.attributes().entrySet()) {
            String value = e.getValue().counts().keySet().iterator().next();
            sb.append(" ").append(qNameToString(e.getKey()))
              .append("=\"").append(escape(value)).append("\"");
        }

        // Directive as XML attribute (Choose deferred to Task 19)
        directiveOpt.ifPresent(d -> writeDirective(sb, d, node, pendingPoolRows));

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
                if (ci instanceof ContentItem.ChildSlot cs) {
                    renderNode(cs.node(), sb, indent + 2, false, pendingPoolRows);
                }
            }
            indent(sb, indent);
        } else {
            // Leaf element
            if (directiveOpt.isPresent()) {
                sb.append("_");
            } else {
                String firstValue = node.valueSamples().distinct().iterator().next();
                sb.append(escape(firstValue));
            }
        }

        sb.append("</").append(qNameToString(node.qName())).append(">\n");
    }

    private void writeDirective(StringBuilder sb, Directive d, ShapeNode node,
                                Map<String, List<Map<String, String>>> pendingPoolRows) {
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
                for (String v : node.valueSamples().distinct()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put(p.column(), v);
                    pendingPoolRows.computeIfAbsent(p.poolName(), k -> new ArrayList<>()).add(row);
                }
            }
            case Directive.Choose c -> throw new UnsupportedOperationException(
                    "Choose directive rendering not yet implemented (Task 19)");
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
