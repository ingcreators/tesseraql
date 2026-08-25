package io.tesseraql.oidc;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The relying party's outbound HTTP to the OpenID Provider — the discovery GET and the
 * token-endpoint POST — through the runtime's {@link OutboundGateway}
 * (docs/duplication-consolidation.md, campaign 1), so both calls obey the same egress policy as
 * every other framework-issued call: the deny-by-default allow-list, the timeouts, the circuit
 * breaker, the {@code tesseraql.http.call} span, and the JVM proxy configuration. What stays
 * here is OIDC's own protocol: the endpoint must be {@code https} (loopback {@code http} is
 * allowed for local development), the response body is bounded, and tokens, codes, and secrets
 * are never logged.
 */
public final class OidcHttp {

    /** Reject discovery/token responses larger than this (both are a few KiB). */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final OutboundGateway gateway;
    private final String requestTimeout;

    public OidcHttp(OutboundGateway gateway, Duration requestTimeout) {
        this.gateway = gateway;
        this.requestTimeout = requestTimeout.toMillis() + "ms";
    }

    /** GETs a JSON document (the discovery endpoint). */
    public byte[] get(URI uri) {
        requireHttps(uri);
        OutboundGateway.RawResponse response = exchange("GET", uri,
                Map.of("Accept", "application/json"), null, "OIDC discovery");
        if (response.status() != 200) {
            throw new OidcException("OIDC discovery failed: HTTP " + response.status());
        }
        return bounded(response.body());
    }

    /**
     * POSTs a {@code application/x-www-form-urlencoded} body (the token exchange) and returns the
     * JSON response. {@code authorization} carries client_secret_basic when set, or null for a
     * public client.
     */
    public String postForm(URI uri, Map<String, String> form, String authorization) {
        requireHttps(uri);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        OutboundGateway.RawResponse response = exchange("POST", uri, headers,
                formEncode(form).getBytes(StandardCharsets.UTF_8), "OIDC token exchange");
        if (response.status() / 100 != 2) {
            throw new OidcException("OIDC token endpoint failed: HTTP " + response.status());
        }
        return new String(bounded(response.body()), StandardCharsets.UTF_8);
    }

    private OutboundGateway.RawResponse exchange(String method, URI uri,
            Map<String, String> headers, byte[] body, String what) {
        try {
            return gateway.exchange(
                    new HttpCallSpec(method, uri.toString(), Map.of(), null, null, null, null,
                            requestTimeout, requestTimeout, null),
                    body, headers);
        } catch (TqlException refused) {
            // The gateway's refusal (denied host, open circuit, transport failure) is already
            // classified; carry its message so the operator reads the allow-list fix, not a
            // bare "failed".
            throw new OidcException(what + " failed: " + refused.getMessage());
        }
    }

    private byte[] bounded(byte[] body) {
        if (body == null || body.length > MAX_BODY_BYTES) {
            throw new OidcException("OIDC response too large");
        }
        return body;
    }

    private static String formEncode(Map<String, String> form) {
        StringJoiner joiner = new StringJoiner("&");
        form.forEach((key, value) -> joiner.add(encode(key) + "=" + encode(value)));
        return joiner.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireHttps(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri)) {
            throw new OidcException("OIDC endpoint must be https: " + uri);
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        // URI.getHost() keeps the brackets on an IPv6 literal, so the bare spelling alone
        // would never match a real URL.
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }
}
