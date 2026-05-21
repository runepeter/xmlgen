package org.brylex.xmlgen.infer;

import java.util.List;
import java.util.Map;

public sealed interface Directive {
    record Repeat(int min, int max) implements Directive {
        public boolean isFixed() { return min == max; }
    }
    record Increment(int step, int startingValue) implements Directive {}
    record Random(Range range) implements Directive {}

    /**
     * Emits a {@code gen:pick="poolName/column"} attribute on a leaf element.
     *
     * <p>{@code poolRows} holds the combined multi-column rows for the pool (all leaves that
     * share this pool). When non-null, the renderer uses these rows directly instead of
     * deriving single-column rows from the leaf's {@link ShapeNode#valueSamples()}.
     * Only the first leaf in the group should carry the non-null poolRows; subsequent leaves
     * carry null and rely on the rows already registered by the first leaf.
     */
    record Pick(String poolName, String column, List<Map<String, String>> poolRows)
            implements Directive {
        /** Convenience constructor for callers that supply no poolRows (legacy / unit tests). */
        public Pick(String poolName, String column) {
            this(poolName, column, null);
        }
        public Pick {
            if (poolRows != null) {
                poolRows = List.copyOf(poolRows);
            }
        }
    }

    record Choose(List<Branch> branches) implements Directive {
        public Choose {
            branches = List.copyOf(branches);
        }
    }

    record Branch(int weight, List<ContentItem> body) {
        public Branch {
            body = List.copyOf(body);
        }
    }
}
