package io.tesseraql.core.rate;

/**
 * The shared budget behind cluster-scoped rate limits (docs/deployment.md, "Cluster rate
 * limits"): every node sharing the main database leases tokens from one per-second window per
 * scope key, so a route limited to N requests/second stays at N across the whole cluster
 * instead of N × node-count. Claims are atomic and first-come-first-served within a window; a
 * quiet node simply leaves budget for the busy ones.
 */
public interface RateBudget {

    /**
     * Atomically claims up to {@code want} tokens from the window's remaining budget.
     *
     * @param scopeKey    the budget identity (app + route)
     * @param windowStart the window's epoch second
     * @param want        tokens requested
     * @param budget      the window's total budget (the declared requests-per-second)
     * @return the tokens actually granted, {@code 0..want}
     */
    int claim(String scopeKey, long windowStart, int want, int budget);
}
