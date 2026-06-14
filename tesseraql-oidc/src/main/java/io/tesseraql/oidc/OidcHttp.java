package io.tesseraql.oidc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The relying party's outbound HTTP to the OpenID Provider — the discovery GET and the token-endpoint
 * POST — with the JDK HTTP client (no external dependency), mirroring {@code HttpJwksFetcher}: the
 * endpoint must be {@code https} (loopback {@code http} is allowed for local development), the body
 * is bounded, and connect/request timeouts apply. Tokens, codes, and secrets are never logged.
 */
public final class OidcHttp {

    /** Reject discovery/token responses larger than this (both are a few KiB). */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final HttpClient client;
    private final Duration requestTimeout;

    public OidcHttp(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** GETs a JSON document (the discovery endpoint). */
    public byte[] get(URI uri) {
        requireHttps(uri);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new OidcException("OIDC discovery failed: HTTP " + response.statusCode());
            }
            return bounded(response.body());
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("OIDC discovery interrupted");
        } catch (Exception ex) {
            throw new OidcException("OIDC discovery failed: " + ex.getMessage());
        }
    }

    /**
     * POSTs a {@code application/x-www-form-urlencoded} body (the token exchange) and returns the
     * JSON response. {@code authorization} carries client_secret_basic when set, or null for a
     * public client.
     */
    public String postForm(URI uri, Map<String, String> form, String authorization) {
        requireHttps(uri);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new OidcException(
                        "OIDC token endpoint failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("OIDC token exchange interrupted");
        } catch (Exception ex) {
            throw new OidcException("OIDC token exchange failed: " + ex.getMessage());
        }
    }

    private byte[] bounded(byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
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
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
