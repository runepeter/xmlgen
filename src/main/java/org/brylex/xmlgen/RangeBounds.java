package org.brylex.xmlgen;

final class RangeBounds {
    /**
     * Parses a {@code "min..max"} specification and returns a two-element array
     * {@code [minStr, maxStr]}.  Throws {@link IllegalArgumentException} if the
     * specification is malformed.
     *
     * @param spec      the raw spec string, e.g. {@code "1..10"}
     * @param directive the directive name used in error messages, e.g. {@code "gen:repeat"}
     */
    static String[] parse(String spec, String directive) {
        int sep = spec.indexOf("..");
        if (sep == 0 || sep < 0 || sep >= spec.length() - 2) {
            throw new IllegalArgumentException(
                    directive + " value must be 'min..max', got '" + spec + "'");
        }
        return new String[]{spec.substring(0, sep).trim(), spec.substring(sep + 2).trim()};
    }

    private RangeBounds() {}
}
