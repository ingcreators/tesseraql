package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The retry policy of one outbound call (docs/connectors.md, "Retry").
 *
 * <p>Transient faults are the normal weather of external APIs, and the surface answered them
 * with a binary {@code onError: fail | empty} — a user-visible failure, or a silently empty
 * panel. A binding opts in; the numbers it leaves out come from
 * {@code tesseraql.http.outbound.retry}. Retry is never applied to a binding that did not ask
 * for it, because it changes the timing and the load a declaration puts on a dependency: an
 * author who reasoned about one attempt must keep getting one.
 *
 * <p>What is retried is the systemic failure the circuit breaker already recognizes — a connect
 * failure, a timeout, a {@code 5xx}. A {@code 4xx} and an {@code expectStatus} mismatch are
 * deterministic rejections; repeating them only spends the dependency's capacity.
 *
 * @param attempts   total attempts including the first, at least 1 and at most
 *                   {@link #MAX_ATTEMPTS}
 * @param backoff    the wait before the second attempt (e.g. {@code 200ms})
 * @param multiplier the factor the wait grows by before each further attempt, at least 1
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetrySpec(Integer attempts, String backoff, Double multiplier) {

    /**
     * The ceiling a declared {@code attempts:} may not exceed. Past a handful, a call is not
     * riding out a blip — it is holding a request thread against a dependency that is down, and
     * the circuit breaker is the mechanism for that.
     */
    public static final int MAX_ATTEMPTS = 10;
}
