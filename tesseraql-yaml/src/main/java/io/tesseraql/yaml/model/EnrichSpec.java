package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * One enrichment of a result set (docs/lookups.md): the rows of {@code into} carry a key, the
 * reference behind that key is fetched, and the match is composed into each row.
 *
 * <p>The key is not declared separately from the join — {@code on:} is a
 * {@code parentColumn: childColumn} map, so its parent side is the key and its child side is
 * what the fetched rows match on. The fetched rows are bound into the source as
 * {@code keys}: a list of values for a single-column key, a list of row maps keyed by the
 * <em>child</em> column names for a composite one, so the SQL file speaks its own vocabulary.
 *
 * <p>The reference is fetched in batches of distinct keys rather than once per row. Splitting
 * is not an optimization: Oracle refuses an {@code IN} list past 1000 expressions and SQL
 * Server a statement past 2100 parameters, so a key set larger than {@code batchSize} becomes
 * several statements whose results merge by key. {@code maxKeys} is the separate ceiling that
 * keeps an unbounded fan-out visible.
 *
 * @param into      the result set to enrich: {@code sql} or a named query
 * @param on        {@code parentColumn: childColumn}, one entry per key column
 * @param sql       the reference query; {@code keys} is bound into it
 * @param http      the reference call; see {@code mode} for what the keys bind to
 * @param mode      {@code batch} (one request for the whole key set) or {@code perRow} (one
 *                  request per distinct key); SQL is always batched, HTTP defaults to perRow
 * @param as        attach the matching rows as a list under this field (one-to-many)
 * @param merge     copy these columns of the single matching row onto each parent (many-to-one)
 * @param batchSize distinct keys per statement; defaulted from the dialect and the key arity
 * @param maxKeys   the ceiling on distinct keys, beyond which the enrichment fails
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnrichSpec(String into, Map<String, String> on, SqlBinding sql,
        HttpSourceSpec http, String mode, String as, List<String> merge, Integer batchSize,
        Integer maxKeys) {

    /** The result set an enrichment enriches when it names none: the route's own SQL. */
    public static final String DEFAULT_INTO = "sql";

    /** One request for the whole key set. */
    public static final String BATCH = "batch";

    /** One request per distinct key. */
    public static final String PER_ROW = "perRow";

    public EnrichSpec {
        on = on == null ? Map.of() : Map.copyOf(on);
        merge = merge == null ? List.of() : List.copyOf(merge);
    }

    /** The result set to enrich, defaulting to the route's own {@code sql:} result. */
    public String effectiveInto() {
        return into == null || into.isBlank() ? DEFAULT_INTO : into;
    }

    /**
     * How the reference is fetched. SQL is always batched — a statement takes a key list by
     * construction. An HTTP reference defaults to {@code perRow}, because most partner APIs are
     * keyed per resource; one that accepts a list declares {@code batch} and gets the same
     * one-round-trip property SQL has (docs/lookups.md, decision 17).
     */
    public String effectiveMode() {
        if (sql != null) {
            return BATCH;
        }
        return mode == null || mode.isBlank() ? PER_ROW : mode;
    }

    /** Whether the whole key set rides one request. */
    public boolean batches() {
        return BATCH.equals(effectiveMode());
    }

    /** Whether this entry merges columns onto the parent rather than attaching a list. */
    public boolean merges() {
        return !merge.isEmpty();
    }

    /** The parent-side key columns, in declaration order. */
    public List<String> keyColumns() {
        return List.copyOf(on.keySet());
    }

    /** The child-side match columns, in the same order as {@link #keyColumns()}. */
    public List<String> matchColumns() {
        return keyColumns().stream().map(on::get).toList();
    }
}
