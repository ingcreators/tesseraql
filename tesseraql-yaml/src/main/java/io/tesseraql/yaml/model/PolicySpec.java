package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route execution policy (design ch. 36.1). The first increment supports a concurrency limit;
 * per-second rate limiting and bulkheads follow.
 *
 * @param concurrency in-flight concurrency limit
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicySpec(Concurrency concurrency) {

    /**
     * In-flight concurrency limit (design ch. 36.1).
     *
     * @param maxInFlight   maximum concurrent in-flight requests for the route
     * @param rejectStatus  HTTP status to reject with when exceeded (default 429)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Concurrency(Integer maxInFlight, Integer rejectStatus) {
    }
}
