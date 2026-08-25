package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * One named {@code http:} source on a query route (docs/connectors.md, "HTTP sources"): a call
 * against an external JSON API at render time, composed with the route's SQL results in the
 * response or view.
 *
 * <p>The call itself is an {@link HttpCallSpec} — the same declaration a job's
 * job step and an enrichment's {@code http:} reference carry, so
 * {@code method}, {@code url}, {@code headers}, {@code query}, {@code body},
 * {@code credential}, {@code expectStatus}, {@code retry} and the timeouts mean one
 * thing everywhere (docs/lookups.md, decision 15). A source adds only what the read side needs: which part of
 * the response becomes rows, and what happens when the call fails.
 *
 * <p>A source is <em>not</em> restricted to GET. That restriction stood for "a read route
 * performs no write", which it neither achieved — nothing stops a partner's GET from mutating
 * — nor came free: it refused JSON-RPC, GraphQL, and every {@code POST …/search} batch-lookup
 * endpoint (docs/lookups.md, decision 16). What holds the line instead is that {@code http:}
 * is unavailable on command routes, so no outbound call is made inside the framework's own
 * write transaction.
 *
 * @param call     the outbound call, in the vocabulary every call site shares
 * @param select   optional dotted path into the response JSON naming the rows array or object
 *                 the source exposes (default: the whole body)
 * @param onError  {@code fail} (default: the request fails) or {@code empty} (the source
 *                 degrades to zero rows and the page still renders)
 * @param readOnly the author's assertion that the call has no side effect, required on a
 *                 command route: the write can roll back and the request cannot
 * @param mode     how the acquired rows are delivered: {@code query} (default — they are held
 *                 and published as {@code rows}) or {@code query-spool} (they are streamed to a
 *                 spool a later chunk step reads). Spooling is not a SQL feature, so it is not
 *                 a key of the {@code sql} arm alone (docs/unified-sources.md decision 19a);
 *                 every arm carries a {@code mode:} and the legal values are the mechanism's,
 *                 which is what keeps the key in one place instead of two
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpSourceSpec(HttpCallSpec call, String select, String onError,
        Boolean readOnly, String mode) {

    /**
     * The flat authoring form: a source's YAML is the call's keys and the source's own, on one
     * level. The mapping lives here so {@link HttpCallSpec} stays the single definition of what
     * a call is — a field added there reaches sources without a second edit.
     */
    @JsonCreator
    public static HttpSourceSpec of(
            @JsonProperty("method") String method,
            @JsonProperty("url") String url,
            @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("query") Map<String, String> query,
            @JsonProperty("credential") String credential,
            @JsonProperty("body") String body,
            @JsonProperty("expectStatus") Integer expectStatus,
            @JsonProperty("connectTimeout") String connectTimeout,
            @JsonProperty("requestTimeout") String requestTimeout,
            @JsonProperty("retry") RetrySpec retry,
            @JsonProperty("select") String select,
            @JsonProperty("onError") String onError,
            @JsonProperty("readOnly") Boolean readOnly,
            @JsonProperty("mode") String mode) {
        return new HttpSourceSpec(new HttpCallSpec(method, url, headers, query, credential, body,
                expectStatus, connectTimeout, requestTimeout, retry), select, onError, readOnly,
                mode);
    }

    /**
     * Whether the author asserted the call has no side effect. A command route requires it: the
     * call happens before the transaction and a rollback cannot un-make it, so the framework
     * guarantees the declaration exists rather than that it is true (docs/lookups.md,
     * decision 19).
     */
    public boolean isReadOnly() {
        return Boolean.TRUE.equals(readOnly);
    }

    /** Whether a failed call degrades to an empty source instead of failing the request. */
    public boolean degradesToEmpty() {
        return "empty".equals(onError);
    }

    /** Whether the acquired rows are streamed to a spool instead of being held. */
    public boolean spools() {
        return "query-spool".equals(mode);
    }

    /** The target URL, for the lint and test surfaces that read it without making the call. */
    public String url() {
        return call.url();
    }
}
