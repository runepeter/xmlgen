package org.brylex.xmlgen.infer;

import java.util.ArrayList;
import java.util.List;

public class AnalysisContext {

    private final InferenceConfig config;
    private final List<InferenceWarning> warnings = new ArrayList<>();

    public AnalysisContext(InferenceConfig config) {
        this.config = config;
    }

    public AnalysisContext(InferenceConfig config, List<InferenceWarning> initialWarnings) {
        this.config = config;
        this.warnings.addAll(initialWarnings);
    }

    public InferenceConfig config() { return config; }

    public void warn(InferenceWarning warning) {
        if (config.strictMode()) {
            throw new InferenceException("strict mode: " + warning.message(),
                    List.of(warning));
        }
        warnings.add(warning);
    }

    public List<InferenceWarning> warnings() {
        return List.copyOf(warnings);
    }
}
