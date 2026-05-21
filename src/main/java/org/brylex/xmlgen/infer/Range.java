package org.brylex.xmlgen.infer;

import java.math.BigDecimal;
import java.time.LocalDate;

public sealed interface Range {
    record IntRange(long min, long max) implements Range {}
    record AmountRange(BigDecimal min, BigDecimal max, int scale) implements Range {}
    record DateRange(LocalDate min, LocalDate max) implements Range {}
    record Uuid() implements Range {}
}
