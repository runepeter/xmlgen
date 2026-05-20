package org.brylex.xmlgen.infer;

import java.util.List;

public class InferenceException extends RuntimeException {

    private final List<InferenceWarning> warnings;

    public InferenceException(String message) {
        this(message, null, List.of());
    }

    public InferenceException(String message, Throwable cause) {
        this(message, cause, List.of());
    }

    public InferenceException(String message, List<InferenceWarning> warnings) {
        this(message, null, warnings);
    }

    public InferenceException(String message, Throwable cause, List<InferenceWarning> warnings) {
        super(message, cause);
        this.warnings = List.copyOf(warnings);
    }

    public List<InferenceWarning> warnings() {
        return warnings;
    }
}
