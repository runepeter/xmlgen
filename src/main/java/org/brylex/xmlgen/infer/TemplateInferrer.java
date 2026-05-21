package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.infer.analyzers.ChooseAnalyzer;
import org.brylex.xmlgen.infer.analyzers.IncrementAnalyzer;
import org.brylex.xmlgen.infer.analyzers.PickCoherenceAnalyzer;
import org.brylex.xmlgen.infer.analyzers.RandomRangeAnalyzer;
import org.brylex.xmlgen.infer.analyzers.RepeatAnalyzer;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import java.util.List;

public final class TemplateInferrer {

    private TemplateInferrer() {}

    public static InferredTemplate infer(List<XMLEventReader> samples) {
        return infer(samples, InferenceConfig.defaults());
    }

    public static InferredTemplate infer(List<XMLEventReader> samples, InferenceConfig config) {
        if (samples.isEmpty()) {
            throw new InferenceException("at least one sample required");
        }

        ShapeBuilder builder = new ShapeBuilder(config);
        for (int i = 0; i < samples.size(); i++) {
            try {
                builder.absorb(samples.get(i));
            } catch (XMLStreamException e) {
                throw new InferenceException("sample " + (i + 1) + " failed to parse", e);
            }
        }
        builder.build();
        ObservationData obs = builder.observationData();

        AnalysisContext ctx = new AnalysisContext(
                config,
                obs.warnings(),
                obs.iterationValues(),
                obs.signatures(),
                obs.rows()
        );

        List<Analyzer> analyzers = List.of(
                new RepeatAnalyzer(),
                new IncrementAnalyzer(),
                new RandomRangeAnalyzer(),
                new ChooseAnalyzer(),
                new PickCoherenceAnalyzer()
        );
        for (Analyzer a : analyzers) {
            a.analyze(obs.root(), ctx);
        }

        TemplateRenderer.Result result = new TemplateRenderer().render(obs.root());
        return new InferredTemplate(result.xml(), result.pools(), ctx.warnings());
    }
}
