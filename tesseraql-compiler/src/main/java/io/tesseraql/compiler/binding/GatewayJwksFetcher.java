package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.jwt.Jwks;
import io.tesseraql.security.jwt.JwksFetcher;
import io.tesseraql.security.jwt.SignatureVerifier;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Map;

/**
 * The production {@link JwksFetcher}: fetches the key set through the runtime's
 * {@link OutboundGateway}, so a JWKS fetch obeys the same egress policy — the deny-by-default
 * allow-list, the timeouts, the circuit breaker, the {@code tesseraql.http.call} span, and the
 * proxy configuration — as every other outbound call (docs/duplication-consolidation.md,
 * campaign 1). It replaced a hand-rolled JDK client in the security module that had drifted from
 * its siblings: it never set a {@code ProxySelector}, so behind a corporate proxy OIDC discovery
 * succeeded and JWKS verification failed against the same IdP.
 *
 * <p>What stays here is the caller's own protocol: the endpoint is pinned to {@code https}
 * (loopback {@code http} allowed for local development), the document is bounded — a real key
 * set is a few KiB — and every failure leaves as the verifier's {@code UNAUTHORIZED}, because a
 * key set that cannot be fetched means the bearer token cannot be verified.
 */
public final class GatewayJwksFetcher implements JwksFetcher {

    /** Reject JWKS documents larger than this (a real key set is a few KiB). */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final OutboundGateway gateway;
    private final String requestTimeout;

    public GatewayJwksFetcher(OutboundGateway gateway, Duration requestTimeout) {
        this.gateway = gateway;
        this.requestTimeout = requestTimeout.toMillis() + "ms";
    }

    @Override
    public Map<String, RSAPublicKey> fetch(URI jwksUri) {
        String scheme = jwksUri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !isLoopback(jwksUri)) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "JWKS URI must be https: " + jwksUri);
        }
        OutboundGateway.RawResponse response;
        try {
            response = gateway.exchange(
                    new HttpCallSpec("GET", jwksUri.toString(),
                            Map.of("Accept", "application/json"), null, null, null, null,
                            requestTimeout, requestTimeout),
                    null, Map.of());
        } catch (TqlException refused) {
            // The gateway's refusal (denied host, open circuit, transport failure) is already
            // classified; what it means HERE is that the bearer cannot be verified.
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "JWKS fetch failed: " + refused.getMessage(), refused);
        }
        if (response.status() != 200) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "JWKS fetch failed: HTTP " + response.status());
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWKS document is empty");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWKS document too large");
        }
        return Jwks.parseJwkSet(body);
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        // URI.getHost() keeps the brackets on an IPv6 literal, so the bare spelling alone
        // would never match a real URL.
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }
}
