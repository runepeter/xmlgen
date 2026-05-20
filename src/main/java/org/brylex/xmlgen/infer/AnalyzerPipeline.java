package org.brylex.xmlgen.infer;

import java.util.List;

public class AnalyzerPipeline {

    private final InferenceConfig config;
    private final List<Analyzer> analyzers;

    public AnalyzerPipeline(InferenceConfig config, List<Analyzer> analyzers) {
        this.config = config;
        this.analyzers = List.copyOf(analyzers);
    }

    public List<InferenceWarning> run(ShapeNode root) {
        return run(root, List.of());
    }

    public List<InferenceWarning> run(ShapeNode root, List<InferenceWarning> initialWarnings) {
        AnalysisContext ctx = new AnalysisContext(config, initialWarnings);
        for (Analyzer a : analyzers) {
            a.analyze(root, ctx);
        }
        return ctx.warnings();
    }
}
