package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route execution policy (design ch. 36.1). The first increment supports a concurrency limit;
 * per-second rate limiting and bulkheads follow.
 *
 * @param concurrency in-flight concurrency limit
 * @param rateLimit   token-bucket rate limit
 * @param lane        the execution lane to dispatch this route onto (design ch. 24), or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicySpec(Concurrency concurrency, RateLimit rateLimit, String lane) {

    /**
     * In-flight concurrency limit (design ch. 36.1).
     *
     * @param maxInFlight   maximum concurrent in-flight requests for the route
     * @param rejectStatus  HTTP status to reject with when exceeded (default 429)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Concurrency(Integer maxInFlight, Integer rejectStatus) {
    }

    /**
     * Token-bucket rate limit (design ch. 36.1; docs/deployment.md "Cluster rate limits").
     *
     * @param requestsPerSecond sustained request rate
     * @param burst             bucket capacity; defaults to requestsPerSecond
     * @param scope             {@code node} (default: each node enforces its own budget) or
     *                          {@code cluster} (the rate is one budget across every node
     *                          sharing the main database, leased in per-second windows)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateLimit(Integer requestsPerSecond, Integer burst, String scope) {

        /** Whether the budget is shared across the cluster rather than per node. */
        public boolean isCluster() {
            return "cluster".equals(scope);
        }
    }
}
