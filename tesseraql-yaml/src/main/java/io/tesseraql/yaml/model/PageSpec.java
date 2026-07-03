package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Declarative pagination for a query route (roadmap Phase 41): the framework appends the
 * dialect's pagination clause at execution time — the authored 2-way SQL stays plain-tool
 * runnable and carries no LIMIT of its own.
 *
 * <p>{@code strategy: offset} (default) pages with a 1-based {@code ?page=} request parameter
 * (and {@code ?size=} up to {@code maxSize} when declared); {@code strategy: keyset} reads an
 * opaque {@code ?after=} cursor the author binds in SQL (e.g.
 * {@code /*%if after != null *&#47; and t.id > /* after *&#47; 0 /*%end*&#47;}) while the
 * framework derives the next cursor from the last row's {@code by:} column. Both fetch one row
 * beyond the page to answer {@code hasNext} without a count; {@code count: true} additionally
 * wraps the query in a {@code select count(*)} for {@code totalRows}/{@code totalPages}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageSpec(String strategy, Integer size, Integer maxSize, boolean count,
        String by) {

    public static final String OFFSET = "offset";
    public static final String KEYSET = "keyset";

    public String effectiveStrategy() {
        return strategy == null || strategy.isBlank() ? OFFSET : strategy;
    }

    public int effectiveSize() {
        return size == null ? 20 : size;
    }
}
