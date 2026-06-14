package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;
import java.util.Map;

/**
 * An {@code http-call} step: a synchronous outbound REST call in a batch pipeline (roadmap
 * Phase 26). The step issues one request to a declared {@link #url} and publishes the response
 * into the job's step context as {@code step.<id>.status} and {@code step.<id>.body}, so a later
 * step can bind it like any other step result.
 *
 * <p>It is a job-pipeline step, never a transactional command step: a command runs every step in
 * one database transaction, and a synchronous outbound call cannot be rolled back, so a command's
 * outbound integration rides the transactional outbox (an HMAC-signed webhook, roadmap Phase 20)
 * instead. A pipeline step runs sequentially and is the right home for request/response REST.
 *
 * <p>The target host must be allow-listed under {@code tesseraql.http.outbound.allowedHosts}
 * (deny by default — Camel's component catalog stays an implementation detail, not user API);
 * credentials, timeouts, and the circuit breaker come from the same configuration so secrets
 * never appear in the step. The {@link #query} values and {@link #body} are source expressions
 * bound from the step context exactly like a SQL step's params; static {@link #headers} values
 * may carry {@code ${...}} config or secret placeholders, resolved at call time.
 *
 * @param method         the HTTP method, defaulting to {@code GET}
 * @param url            the absolute target URL; {@code ${...}} config placeholders are allowed,
 *                       but the host must resolve statically so it can be allow-listed and linted
 * @param headers        static request headers; values may be {@code ${...}} placeholders
 * @param query          query parameters bound from the step context (name to source expression)
 * @param credential     a named credential under {@code tesseraql.http.outbound.credentials}
 * @param body           a source expression resolving to the JSON request body (e.g. a prior
 *                       step's rows); ignored for methods without a body
 * @param expectStatus   a specific success status to require; unset, any 2xx is success
 * @param connectTimeout per-step connect-timeout override (e.g. {@code 5s}), else the config default
 * @param requestTimeout per-step request-timeout override (e.g. {@code 30s}), else the config default
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpCallSpec(
        String method,
        String url,
        Map<String, String> headers,
        Map<String, String> query,
        String credential,
        String body,
        @JsonProperty("expectStatus") Integer expectStatus,
        String connectTimeout,
        String requestTimeout) {

    public HttpCallSpec {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        query = query == null ? Map.of() : Map.copyOf(query);
    }

    /** The HTTP method in upper case, defaulting to {@code GET}. */
    public String effectiveMethod() {
        return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
    }
}
