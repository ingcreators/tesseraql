package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One code catalog (docs/lookups.md, decisions 8-10): a small, nearly static table of codes and
 * the names they stand for, held whole rather than looked up per key.
 *
 * <p>The choice between a catalog and an {@code enrich:} reference is size, not key arity. A
 * master of tens or hundreds of rows that barely changes is loaded once and resolved from
 * memory — twenty of them cost twenty small queries per refresh and none per request. A master
 * of thousands that moves is fetched by key.
 *
 * <p>Most code masters are one table holding many kinds, keyed by a type column. That shape is
 * why {@code where:} exists: it fixes the type here, so the catalog itself is single-keyed and
 * a field domain can reference it (decision 9).
 *
 * @param table      the table the codes live in; exactly one of {@code table} or {@code file}
 * @param file       a 2-way SQL file, for a shape {@code table}/{@code where} cannot express
 * @param where      equality filters pinning this catalog's slice of a shared table
 * @param key        the column carrying the code
 * @param label      the column carrying the name
 * @param order      an optional display-order column for a form's options
 * @param active     an optional column whose truth marks a code still offered and accepted;
 *                   labels resolve over every row, so a retired code still renders on old data
 * @param datasource the connector the catalog loads from, defaulting to the app's main
 * @param cache      how long a load is held before it is read again
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogSpec(String table, String file, Map<String, String> where, String key,
        String label, String order, String active, String datasource, CacheSpec cache) {

    /** How long a catalog is held when it does not say: long enough that a request never loads. */
    public static final String DEFAULT_TTL = "1h";

    public CatalogSpec {
        where = where == null ? Map.of() : Map.copyOf(where);
    }

    /** The connector this catalog loads from. */
    public String effectiveDatasource() {
        return datasource == null || datasource.isBlank() ? "main" : datasource;
    }

    /** The hold time before the next read of the source. */
    public String effectiveTtl() {
        return cache == null || cache.maxAge() == null || cache.maxAge().isBlank()
                ? DEFAULT_TTL
                : cache.maxAge();
    }
}
