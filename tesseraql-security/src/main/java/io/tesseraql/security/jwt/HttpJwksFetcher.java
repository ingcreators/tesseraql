package io.tesseraql.security.jwt;

import io.tesseraql.core.error.TqlException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Map;

/**
 * Fetches a JWKS document over HTTPS with the JDK HTTP client (design ch. 11.1) — no external
 * dependency. The endpoint is operator-configured, so the SSRF surface is small; the fetcher still
 * pins {@code https} (loopback {@code http} is allowed for local development), bounds the response
 * body, and applies connect/request timeouts. Parsing is delegated to {@link Jwks}.
 */
public final class HttpJwksFetcher implements JwksFetcher {

    /** Reject JWKS documents larger than this (a real key set is a few KiB). */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpJwksFetcher(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Map<String, RSAPublicKey> fetch(URI jwksUri) {
        String scheme = jwksUri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !isLoopback(jwksUri)) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "JWKS URI must be https: " + jwksUri);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(jwksUri).timeout(requestTimeout)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                        "JWKS fetch failed: HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_BODY_BYTES) {
                throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWKS document too large");
            }
            return Jwks.parseJwkSet(body);
        } catch (TqlException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWKS fetch interrupted");
        } catch (Exception ex) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "JWKS fetch failed: " + ex.getMessage());
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
