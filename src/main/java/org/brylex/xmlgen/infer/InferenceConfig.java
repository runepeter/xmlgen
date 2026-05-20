package org.brylex.xmlgen.infer;

public record InferenceConfig(
        double lowCardinalityRatio,
        int lowCardinalityMaxDistinct,
        double randomCardinalityThreshold,
        int minPoolRows,
        double chooseNoiseThreshold,
        int chooseMaxBranches,
        int maxValueSamplesPerNode,
        int maxSampleDepth,
        boolean allowVariableAttributes,
        boolean strictMode
) {
    public static InferenceConfig defaults() {
        return new InferenceConfig(
                0.20,   // lowCardinalityRatio
                50,     // lowCardinalityMaxDistinct
                0.50,   // randomCardinalityThreshold
                5,      // minPoolRows
                0.10,   // chooseNoiseThreshold
                4,      // chooseMaxBranches
                1000,   // maxValueSamplesPerNode
                100,    // maxSampleDepth
                false,  // allowVariableAttributes
                false   // strictMode
        );
    }
}
