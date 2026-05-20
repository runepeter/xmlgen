package org.brylex.xmlgen.infer;

public sealed interface InferenceWarning {

    String xpath();
    String message();

    record DroppedRareSignature(String xpath, String signatureRepr, int count, int totalObservations) implements InferenceWarning {
        @Override public String message() {
            return "dropped rare signature " + signatureRepr + " at " + xpath
                    + " (" + count + "/" + totalObservations + ")";
        }
    }

    record ChooseBranchOverflow(String xpath, int branchCount, int max) implements InferenceWarning {
        @Override public String message() {
            return "choose at " + xpath + " has " + branchCount + " branches, capped at " + max;
        }
    }

    record AmbiguousOrder(String xpath, String chosenSequence, String alternativeSequence) implements InferenceWarning {
        @Override public String message() {
            return "ambiguous child order at " + xpath + "; chose " + chosenSequence
                    + " over " + alternativeSequence;
        }
    }

    record VariableAttributeFallback(String xpath, String attributeName, int distinctValues) implements InferenceWarning {
        @Override public String message() {
            return "attribute " + attributeName + " at " + xpath + " has "
                    + distinctValues + " distinct values; using first-observed";
        }
    }

    record ValueSamplesTruncated(String xpath, int kept, int total) implements InferenceWarning {
        @Override public String message() {
            return "value samples at " + xpath + " truncated: kept " + kept + " of " + total;
        }
    }

    record UniqueValueCycleRisk(String xpath, int observations) implements InferenceWarning {
        @Override public String message() {
            return "all " + observations + " observed values at " + xpath
                    + " are unique; pool will cycle on expansion";
        }
    }

    record MixedTypeFallback(String xpath, String reason) implements InferenceWarning {
        @Override public String message() {
            return "field at " + xpath + " has mixed types: " + reason;
        }
    }

    record PartialBijectionRejected(String xpath, double bijectionRatio) implements InferenceWarning {
        @Override public String message() {
            return "partial bijection at " + xpath + " (ratio=" + bijectionRatio
                    + "); using individual picks";
        }
    }
}
