package io.tesseraql.core.dialect;

import java.util.List;

/**
 * Generates a dialect-appropriate pagination clause from {@link DialectCapabilities} (design ch. 42.2).
 * Dialects that support {@code OFFSET ... FETCH} (Oracle, SQL Server) get that form; the rest get
 * {@code LIMIT ... OFFSET} (PostgreSQL, MySQL). The bind values are returned already ordered to match
 * the {@code ?} placeholders, since the two forms order limit and offset differently.
 */
public final class Pagination {

    private Pagination() {
    }

    /** A pagination clause with {@code ?} placeholders and the bind values in placeholder order. */
    public record Clause(String sql, List<Object> parameters) {
    }

    /** Builds the pagination clause for {@code limit} rows starting at {@code offset}. */
    public static Clause clause(DialectCapabilities capabilities, long limit, long offset) {
        if (capabilities.offsetFetch() && !capabilities.limitOffset()) {
            return new Clause("offset ? rows fetch next ? rows only", List.of(offset, limit));
        }
        return new Clause("limit ? offset ?", List.of(limit, offset));
    }

    /** Builds the pagination clause for a dialect. */
    public static Clause clause(Dialect dialect, long limit, long offset) {
        return clause(dialect.capabilities(), limit, offset);
    }
}
