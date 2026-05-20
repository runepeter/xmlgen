package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceConfigTest {

    @Test
    void defaultsHaveSensibleValues() {
        InferenceConfig cfg = InferenceConfig.defaults();
        assertThat(cfg.lowCardinalityRatio()).isEqualTo(0.20);
        assertThat(cfg.lowCardinalityMaxDistinct()).isEqualTo(50);
        assertThat(cfg.randomCardinalityThreshold()).isEqualTo(0.50);
        assertThat(cfg.minPoolRows()).isEqualTo(5);
        assertThat(cfg.chooseNoiseThreshold()).isEqualTo(0.10);
        assertThat(cfg.chooseMaxBranches()).isEqualTo(4);
        assertThat(cfg.maxValueSamplesPerNode()).isEqualTo(1000);
        assertThat(cfg.maxSampleDepth()).isEqualTo(100);
        assertThat(cfg.allowVariableAttributes()).isFalse();
        assertThat(cfg.strictMode()).isFalse();
    }
}
