package org.brylex.xmlgen.infer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalysisContext {

    private final InferenceConfig config;
    private final List<InferenceWarning> warnings = new ArrayList<>();
    private final Map<String, List<List<String>>> iterationValues;

    public AnalysisContext(InferenceConfig config) {
        this(config, List.of(), Map.of());
    }

    public AnalysisContext(InferenceConfig config, List<InferenceWarning> initialWarnings) {
        this(config, initialWarnings, Map.of());
    }

    public AnalysisContext(InferenceConfig config, List<InferenceWarning> initialWarnings,
                           Map<String, List<List<String>>> iterationValues) {
        this.config = config;
        this.warnings.addAll(initialWarnings);
        this.iterationValues = Map.copyOf(iterationValues);
    }

    public InferenceConfig config() { return config; }

    /**
     * Returns, per leaf xpath, the list of iteration-value sequences observed across
     * all samples. Each inner list is the ordered sequence of values seen within one
     * repeat-parent-instance (e.g. all seq values across the line children of one order).
     */
    public List<List<String>> iterationValuesAt(String xpath) {
        return iterationValues.getOrDefault(xpath, List.of());
    }

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
