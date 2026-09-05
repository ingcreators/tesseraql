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
     * The largest {@code ?page=} this framework accepts (docs/http-edge-robustness.md
     * decision 9).
     *
     * <p>A page number is framework-owned and was bounded only from below, so everything above 1
     * flowed into three products: the offset leg's {@code (number - 1) * size} and the snapshot
     * leg's two slice bounds. Near {@code Long.MAX_VALUE} those wrap — a refused OFFSET rendered
     * 500 on the offset leg, an {@code IndexOutOfBoundsException} out of {@code subList} on the
     * snapshot leg — against a contract that promises a field-scoped 400 for a bad value.
     *
     * <p>The ceiling is flat rather than derived from the declared size. With
     * {@code number <= Integer.MAX_VALUE} and {@code size} an {@code int}, the largest product
     * any of those expressions can form is 4,611,686,011,984,936,962 — comfortably inside
     * {@code long} — so one constant removes the overflow from every site at once instead of a
     * divisor re-derived per route.
     *
     * <p>Pagination renderers may emit a link one past this at the ceiling, and that is left
     * unclamped deliberately: the offset leg would need a real row at offset 42,949,672,920 and
     * the snapshot leg's membership is capped at 500, so no result set can reach it.
     */
    public static final long MAX_PAGE = Integer.MAX_VALUE;

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
