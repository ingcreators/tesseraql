package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
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
 * <p>A shape a table and equality filters cannot express — a join to a table of per-language
 * names — declares {@code file:} instead, and then lists the {@code tables:} it reads, because
 * invalidation must not require anything to parse SQL (decision 13).
 *
 * @param table      the table the codes live in ({@code file:}'s alternative)
 * @param file       a SQL file whose result set is the catalog, for a shape {@code table:} and
 *                   equality filters cannot express; resolved under the app's {@code catalogs/}
 * @param tables     with {@code file:}, the source tables the SQL reads, so a maintenance
 *                   command's {@code invalidates:} can reach this catalog
 * @param where      equality filters pinning this catalog's slice of a shared table
 * @param key        the column carrying the code
 * @param label      where the name comes from: a result column, or a message-catalog key
 * @param language   an optional column carrying the BCP-47 tag a row's name is written in;
 *                   language is a dimension of the catalog, not part of its key, so the call
 *                   site is the same in every language and only the labels differ
 * @param order      an optional display-order column for a form's options
 * @param active     an optional column whose truth marks a code still offered and accepted;
 *                   labels resolve over every row, so a retired code still renders on old data
 * @param datasource the connector the catalog loads from, defaulting to the app's main
 * @param cache      how long a load is held before it is read again
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogSpec(String table, String file, List<String> tables,
        Map<String, String> where, String key, LabelSource label, String language, String order,
        String active, String datasource, CacheSpec cache) {

    /** TQL-FIELD-4621: a catalog's source declaration is contradictory or incomplete. */
    public static final TqlErrorCode INVALID_SOURCE = new TqlErrorCode(TqlDomain.FIELD, 4621);

    /** How long a catalog is held when it does not say: long enough that a request never loads. */
    public static final String DEFAULT_TTL = "1h";

    /**
     * Where a code's name comes from (docs/lookups.md, decision 12). Either a column of the
     * load's result set — {@code label: 区分名称} — or a message-catalog key with the code
     * interpolated — {@code label: { message: "code.取引区分.{key}" }}.
     *
     * <p>The message form exists because a name is often already a translated string, and
     * putting it in the message catalog puts it in the translation workflow the Studio message
     * editor already serves instead of inventing a per-language table beside it. It is the same
     * key either way, because the label is the label however it is sourced.
     */
    public record LabelSource(String column, String message) {

        @JsonCreator
        static LabelSource of(Object raw) {
            if (raw instanceof String column) {
                return new LabelSource(column, null);
            }
            if (raw instanceof Map<?, ?> map && map.get("message") != null) {
                return new LabelSource(null, String.valueOf(map.get("message")));
            }
            throw new TqlException(INVALID_SOURCE, "A catalog's label: is a column name or"
                    + " { message: <key with {key}> }, not " + raw);
        }

        /** The message key for one code, or {@code null} when the label is a column. */
        public String messageFor(Object code) {
            return message == null ? null : message.replace("{key}", String.valueOf(code));
        }
    }

    public CatalogSpec {
        where = where == null ? Map.of() : Map.copyOf(where);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /**
     * Fails a contradictory or incomplete source declaration at load, where the message can
     * name the catalog, rather than at first use behind a half-built SELECT.
     */
    public void validate(String name) {
        boolean hasTable = present(table);
        boolean hasFile = present(file);
        if (hasTable == hasFile) {
            throw new TqlException(INVALID_SOURCE, "Catalog '" + name + "' declares "
                    + (hasTable ? "both table: and file:" : "neither table: nor file:")
                    + " — a catalog reads one source");
        }
        if (!present(key) || label == null) {
            throw new TqlException(INVALID_SOURCE, "Catalog '" + name
                    + "' needs key: and label:");
        }
        if (hasFile) {
            // The SQL owns its filtering and its ordering. Accepting where:/order: beside it
            // would leave two places to look for either, one of which does nothing.
            if (!where.isEmpty() || present(order)) {
                throw new TqlException(INVALID_SOURCE, "Catalog '" + name + "' declares file:"
                        + " with where:/order: — the SQL owns both");
            }
            if (tables.isEmpty()) {
                throw new TqlException(INVALID_SOURCE, "Catalog '" + name + "' declares file:"
                        + " but no tables: — invalidation cannot find it, and nothing parses"
                        + " SQL to work it out");
            }
        }
    }

    /** The source tables this catalog reads: the declared list, or the single {@code table:}. */
    public List<String> sourceTables() {
        return present(table) ? List.of(table) : tables;
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

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
