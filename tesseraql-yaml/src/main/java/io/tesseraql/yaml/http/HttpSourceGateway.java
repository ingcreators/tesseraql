package io.tesseraql.yaml.http;

import io.tesseraql.yaml.model.HttpCallSpec;
import java.util.Map;

/**
 * The outbound gateway a query route's {@code http:} sources execute through
 * (docs/connectors.md, "HTTP sources"): the runtime binds the job pipeline's http-call client
 * behind this seam, so route sources inherit the exact same egress discipline — the
 * deny-by-default allow-list, named secret-managed credentials, timeouts, and the per-host
 * circuit breaker — with no second HTTP stack.
 */
public interface HttpSourceGateway {

    /**
     * Performs the resolved call and returns the {@code {status, body, headers}} map the job
     * client publishes; the body is parsed JSON when the response declares it.
     */
    Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context);
}
