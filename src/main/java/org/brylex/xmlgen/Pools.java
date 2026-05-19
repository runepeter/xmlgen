package org.brylex.xmlgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Container of named {@link Pool}s consulted by {@code gen:pick}.
 * Build with {@link #builder()}; pass the result into
 * {@link GeneratingXMLEventReader}.
 */
public final class Pools {

    private static final Pools EMPTY = new Pools(Map.of());

    private final Map<String, Pool> pools;

    private Pools(Map<String, Pool> pools) {
        this.pools = Map.copyOf(pools);
    }

    public static Pools empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return pools.isEmpty();
    }

    public Pool get(String name) {
        Pool pool = pools.get(name);
        if (pool == null) {
            throw new IllegalArgumentException(
                    "Unknown pool '" + name + "'. Known pools: " + pools.keySet());
        }
        return pool;
    }

    public static final class Builder {

        private final Map<String, Pool> pools = new LinkedHashMap<>();

        public Builder inline(String name, List<Map<String, String>> rows) {
            pools.put(name, new Pool(name, rows));
            return this;
        }

        public Builder csv(String name, Path path) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                return csv(name, r);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read CSV for pool '" + name + "'", e);
            }
        }

        public Builder csv(String name, Reader reader) {
            try (BufferedReader br = new BufferedReader(reader)) {
                String header = br.readLine();
                if (header == null) {
                    throw new IllegalArgumentException(
                            "CSV for pool '" + name + "' is empty");
                }
                String[] cols = splitTrimmed(header);
                List<Map<String, String>> rows = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] vals = splitTrimmed(line);
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < cols.length; i++) {
                        row.put(cols[i], i < vals.length ? vals[i] : "");
                    }
                    rows.add(row);
                }
                return inline(name, rows);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read CSV for pool '" + name + "'", e);
            }
        }

        public Pools build() {
            return new Pools(pools);
        }

        private static String[] splitTrimmed(String line) {
            String[] parts = line.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            return parts;
        }
    }
}
