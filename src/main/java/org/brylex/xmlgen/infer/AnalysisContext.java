package org.brylex.xmlgen.infer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalysisContext {

    private final InferenceConfig config;
    private final List<InferenceWarning> warnings = new ArrayList<>();
    private final Map<String, List<List<String>>> iterationValues;
    private final Map<String, List<Signature>> signaturesPerXpath;
    private final Map<String, List<Map<String, String>>> rowsPerXpath;

    public AnalysisContext(InferenceConfig config) {
        this(config, List.of(), Map.of(), Map.of(), Map.of());
    }

    public AnalysisContext(InferenceConfig config, List<InferenceWarning> initialWarnings) {
        this(config, initialWarnings, Map.of(), Map.of(), Map.of());
    }

    public AnalysisContext(InferenceConfig config, List<InferenceWarning> initialWarnings,
                           Map<String, List<List<String>>> iterationValues) {
        this(config, initialWarnings, iterationValues, Map.of(), Map.of());
    }

    public AnalysisContext(InferenceConfig config, List<InferenceWarning> initialWarnings,
                           Map<String, List<List<String>>> iterationValues,
                           Map<String, List<Signature>> signaturesPerXpath) {
        this(config, initialWarnings, iterationValues, signaturesPerXpath, Map.of());
    }

    public AnalysisContext(InferenceConfig config,
                           List<InferenceWarning> initialWarnings,
                           Map<String, List<List<String>>> iterationValues,
                           Map<String, List<Signature>> signaturesPerXpath,
                           Map<String, List<Map<String, String>>> rowsPerXpath) {
        this.config = config;
        this.warnings.addAll(initialWarnings);
        this.iterationValues = Map.copyOf(iterationValues);
        this.signaturesPerXpath = Map.copyOf(signaturesPerXpath);
        this.rowsPerXpath = Map.copyOf(rowsPerXpath);
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

    /**
     * Returns, for the given parent xpath, the list of child-signatures observed across
     * all parent-instances. Each {@link Signature} is the ordered (qName, cardinality-bucket)
     * sequence seen inside one parent-instance occurrence.
     *
     * <p>Populated by ShapeBuilder (wired in Task 20). Unit tests supply data directly via
     * the four-arg constructor.
     */
    public List<Signature> signaturesAt(String xpath) {
        return signaturesPerXpath.getOrDefault(xpath, List.of());
    }

    /**
     * Returns, for the given parent xpath, the list of per-instance rows observed across
     * all parent-instance occurrences. Each {@link Map} maps leaf-local-name → observed-value
     * for one parent-instance observation.
     *
     * <p>Populated by ShapeBuilder (wired in Task 20). Unit tests supply data directly via
     * the five-arg constructor.
     */
    public List<Map<String, String>> rowsAt(String parentXpath) {
        return rowsPerXpath.getOrDefault(parentXpath, List.of());
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
