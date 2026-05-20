package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pools;

import javax.xml.stream.XMLEventReader;
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
        // Stub: full pipeline wired in Task 20. For now, emit a placeholder template
        // so dependent stub tests pass.
        return new InferredTemplate(
                "<root xmlns:gen=\"urn:xml:gen\"/>",
                Pools.empty(),
                List.of()
        );
    }
}
