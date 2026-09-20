package io.tesseraql.core.cache;

import java.util.List;

/**
 * How long a statement's rows are held and which tables they read (docs/caching.md decision
 * 2): what a source's {@code cache: {maxAge, tables}} compiles to, carried by the step that
 * executes the statement so the hold can key and drop its entries.
 *
 * @param owner        the document the source belongs to — a route or tool id — named on the
 *                     counters and the operations surface
 * @param source       the source's own name ({@code main}, {@code byCategory})
 * @param datasource   the connector name the statement runs on, before tenant routing; part of
 *                     the key, because two connectors may serve the same statement text
 * @param maxAgeMillis how long an entry serves before the statement runs again; positive
 * @param tables       the tables the statement reads — what a writer's {@code invalidates:}
 *                     names to drop the entries; never empty (decision 2)
 */
public record HoldSpec(String owner, String source, String datasource, long maxAgeMillis,
        List<String> tables) {

    public HoldSpec {
        if (maxAgeMillis <= 0) {
            throw new IllegalArgumentException("maxAgeMillis must be positive");
        }
        tables = List.copyOf(tables);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("tables must name at least one table");
        }
        datasource = datasource == null || datasource.isBlank() ? "main" : datasource;
    }

    /** {@code owner/source}: the label the counters and the operations row carry. */
    public String label() {
        return owner + "/" + source;
    }
}
