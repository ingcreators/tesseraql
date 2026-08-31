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
 * framework derives the next cursor from the last row's {@code by:} column. A composite
 * cursor declares {@code by:} as an ordered list (docs/list-surface.md decision 5): the next
 * cursor becomes one opaque row token, and the framework decodes an incoming {@code ?after=}
 * into {@code params.after.<column>} parts for the authored tuple predicate to bind. Both
 * strategies fetch one row beyond the page to answer {@code hasNext} without a count;
 * {@code count: true} additionally wraps the query in a {@code select count(*)} for
 * {@code totalRows}/{@code totalPages}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageSpec(String strategy, Integer size, Integer maxSize, boolean count,
        @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) java.util.List<String> by,
        // The snapshot membership cap (docs/list-surface.md decision 10): a search whose hits
        // exceed it is refused rather than truncated. Only legal on strategy: snapshot.
        Integer cap) {

    public static final String OFFSET = "offset";
    public static final String KEYSET = "keyset";

    /**
     * The work-queue strategy (docs/list-surface.md decision 10): membership frozen at search
     * time as the row tokens the page carries, row state fetched live per page.
     */
    public static final String SNAPSHOT = "snapshot";

    /** The snapshot membership cap, defaulting to 500 (the result-cap hard-reject shape). */
    public int effectiveCap() {
        return cap == null ? 500 : cap;
    }

    public String effectiveStrategy() {
        return strategy == null || strategy.isBlank() ? OFFSET : strategy;
    }

    public int effectiveSize() {
        return size == null ? 20 : size;
    }

    /** The cursor columns, in declaration order — empty when the route declares none. */
    public java.util.List<String> effectiveBy() {
        return by == null ? java.util.List.of() : by;
    }
}
