package org.brylex.xmlgen.infer.analyzers;

import org.brylex.xmlgen.infer.AnalysisContext;
import org.brylex.xmlgen.infer.Analyzer;
import org.brylex.xmlgen.infer.ContentItem;
import org.brylex.xmlgen.infer.Directive;
import org.brylex.xmlgen.infer.ShapeNode;

public class RepeatAnalyzer implements Analyzer {

    @Override
    public void analyze(ShapeNode root, AnalysisContext ctx) {
        walk(root);
    }

    private void walk(ShapeNode node) {
        for (ContentItem ci : node.orderedContent()) {
            if (ci instanceof ContentItem.ChildSlot cs) {
                ShapeNode child = cs.node();
                long count = child.cardinalityPerParent().getCount();
                if (count > 0) {
                    int min = (int) child.cardinalityPerParent().getMin();
                    int max = (int) child.cardinalityPerParent().getMax();
                    if (max > 1) {
                        child.setDirective(new Directive.Repeat(min, max));
                    }
                }
                walk(child);
            }
        }
    }
}
