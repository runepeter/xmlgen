package org.brylex.xmlgen.infer;

import java.util.List;

public sealed interface Directive {
    record Repeat(int min, int max) implements Directive {
        public boolean isFixed() { return min == max; }
    }
    record Increment(int step, int startingValue) implements Directive {}
    record Random(Range range) implements Directive {}
    record Pick(String poolName, String column) implements Directive {}
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
