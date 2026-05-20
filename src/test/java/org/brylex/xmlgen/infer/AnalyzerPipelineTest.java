package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzerPipelineTest {

    @Test
    void runsAnalyzersInOrderAndCollectsWarnings() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        Analyzer warner = (n, ctx) -> ctx.warn(new InferenceWarning.AmbiguousOrder(
                "/order", "(a,b)", "(b,a)"));
        Analyzer noop = (n, ctx) -> {};

        AnalyzerPipeline pipeline = new AnalyzerPipeline(InferenceConfig.defaults(),
                List.of(warner, noop));
        List<InferenceWarning> warnings = pipeline.run(root);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).isInstanceOf(InferenceWarning.AmbiguousOrder.class);
    }

    @Test
    void strictModePromotesWarningToException() {
        ShapeNode root = new ShapeNode(new QName("order"), "/order");
        InferenceConfig strict = new InferenceConfig(
                0.20, 50, 0.50, 5, 0.10, 4, 1000, 100, false, true);
        Analyzer warner = (n, ctx) -> ctx.warn(new InferenceWarning.AmbiguousOrder(
                "/order", "(a,b)", "(b,a)"));
        AnalyzerPipeline pipeline = new AnalyzerPipeline(strict, List.of(warner));

        assertThatThrownBy(() -> pipeline.run(root))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("strict mode");
    }
}
