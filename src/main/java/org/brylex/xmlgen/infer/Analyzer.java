package org.brylex.xmlgen.infer;

@FunctionalInterface
public interface Analyzer {
    void analyze(ShapeNode root, AnalysisContext ctx);
}
