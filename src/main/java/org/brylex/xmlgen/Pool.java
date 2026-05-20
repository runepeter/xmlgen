package org.brylex.xmlgen;

import java.util.List;
import java.util.Map;

/**
 * A named, ordered collection of records (rows) used as a value source
 * for {@code gen:pick} substitutions. Each row is a map of column name to
 * value. Picks cycle through rows in order, wrapping at the end.
 */
public final class Pool {

    private final String name;
    private final List<Map<String, String>> rows;
    private int cursor;

    public Pool(String name, List<Map<String, String>> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Pool '" + name + "' must have at least one row");
        }
        this.name = name;
        this.rows = List.copyOf(rows);
    }

    public String name() {
        return name;
    }

    public int size() {
        return rows.size();
    }

    public List<Map<String, String>> rows() {
        return rows;
    }

    /** Advance the cursor and return the previous index (the row to use now). */
    int nextRow() {
        int row = cursor;
        cursor = (cursor + 1) % rows.size();
        return row;
    }

    String value(int row, String column) {
        Map<String, String> r = rows.get(row);
        if (!r.containsKey(column)) {
            throw new IllegalArgumentException(
                    "Pool '" + name + "' has no column '" + column + "'. Available: " + r.keySet());
        }
        return r.get(column);
    }
}
