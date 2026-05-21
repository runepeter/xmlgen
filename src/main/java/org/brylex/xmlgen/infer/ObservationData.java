package org.brylex.xmlgen.infer;

import java.util.List;
import java.util.Map;

/**
 * Bundles all per-xpath observation data produced by {@link ShapeBuilder} during
 * {@link ShapeBuilder#absorb(javax.xml.stream.XMLEventReader)} walks.
 *
 * <p>Passed to {@link AnalysisContext} so analyzers can access the raw evidence
 * collected from XML samples without re-scanning the merged {@link ShapeNode} tree.
 */
public record ObservationData(
        ShapeNode root,
        List<InferenceWarning> warnings,
        Map<String, List<List<String>>> iterationValues,
        Map<String, List<Signature>> signatures,
        Map<String, List<Map<String, String>>> rows
) {
    public ObservationData {
        warnings = List.copyOf(warnings);
        iterationValues = Map.copyOf(iterationValues);
        signatures = Map.copyOf(signatures);
        rows = Map.copyOf(rows);
    }
}
