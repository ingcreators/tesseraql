package io.tesseraql.yaml.http;

import io.tesseraql.yaml.model.HttpCallSpec;
import java.util.Map;

/**
 * The one seam every outbound HTTP call leaves through (docs/lookups.md, decision 15): a job's
 * job step's {@code http:} arm, a query route's {@code http:} source, a command's pre-transaction
 * fetch, an enrichment's HTTP reference, and a webhook notification's delivery. The runtime
 * binds the job pipeline's client behind it, so all of them inherit the same egress discipline
 * — the deny-by-default allow-list, named secret-managed credentials, timeouts, and the
 * per-host circuit breaker — with no second HTTP stack anywhere.
 */
public interface OutboundGateway {

    /**
     * Performs the resolved call and returns the {@code {status, body, headers}} map the job
     * client publishes; the body is parsed JSON when the response declares it. The request body,
     * when the spec declares one, is the context value {@code spec.body()} names.
     */
    Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context);

    /**
     * The same call with the request body and some headers supplied by the caller rather than
     * resolved from a context path.
     *
     * <p>This exists for a signed delivery. A webhook's HMAC covers the exact bytes that go on
     * the wire, so the bytes the signature was computed over must be the bytes sent — a second,
     * independent serialization inside the gateway could differ and every receiver would reject
     * the signature. The caller therefore keeps authorship of the body and the per-request
     * headers, and the gateway contributes what it is for: the policy.
     */
    Map<String, Object> call(HttpCallSpec spec, byte[] body, Map<String, String> headers);
}
