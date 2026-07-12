package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * One named {@code http:} source on a query route (docs/connectors.md, "HTTP sources"): a GET
 * against an external JSON API at render time, composed with the route's SQL results in the
 * response or view. Deliberately a strict subset of a job's {@code http-call:} step — always
 * GET, never a body — because a query route must stay a read; it executes through the same
 * outbound gateway (deny-by-default allow-list, named credentials, timeouts, circuit breaker).
 *
 * @param url            the absolute http(s) URL; config placeholders resolve
 * @param headers        extra request headers
 * @param query          query-string bindings, each an expression over the execution context
 * @param credential     a named credential under {@code tesseraql.http.outbound.credentials}
 * @param expectStatus   the status treated as success (default 200)
 * @param connectTimeout connect-timeout duration override
 * @param requestTimeout request-timeout duration override
 * @param select         optional dotted path into the response JSON naming the rows array or
 *                       object the source exposes (default: the whole body)
 * @param onError        {@code fail} (default: the request fails) or {@code empty} (the source
 *                       degrades to zero rows and the page still renders)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpSourceSpec(
        String url,
        Map<String, String> headers,
        Map<String, String> query,
        String credential,
        @JsonProperty("expectStatus") Integer expectStatus,
        String connectTimeout,
        String requestTimeout,
        String select,
        String onError) {

    public HttpSourceSpec {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        query = query == null ? Map.of() : Map.copyOf(query);
    }

    /** Whether a failed call degrades to an empty source instead of failing the request. */
    public boolean degradesToEmpty() {
        return "empty".equals(onError);
    }

    /** The equivalent {@code http-call} step the outbound gateway executes: a body-less GET. */
    public HttpCallSpec toCall() {
        return new HttpCallSpec("GET", url, headers, query, credential, null, expectStatus,
                connectTimeout, requestTimeout);
    }
}
