package io.tesseraql.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The intra-stack hop (docs/duplication-consolidation.md, campaign 1): a shell surface calling a
 * member runtime on its own live loopback port with the caller's forwarded credentials. Five
 * surfaces each carried their own copy of this program — a per-call or even per-request
 * {@code HttpClient} (each owns a selector thread and a connection pool released only at GC),
 * hand-picked forwarded headers, hand-rolled form encoding, and an interrupt dance — and they
 * had drifted: one leaked a client per request, one bounded only the response headers.
 *
 * <p>Deliberately <em>not</em> the {@code OutboundGateway}: loopback is not egress. The
 * allow-list answers "what may this stack reach outside itself", and localhost is deliberately
 * absent from it; forcing operators to allow-list their own stack would weaken the posture the
 * list exists for. What a loopback hop needs instead is here: one shared client, a mandatory
 * per-call timeout (no form exists without one), the forwarded credential headers named in one
 * reviewable place, and a single failure signal the caller maps to its own refusal — the member
 * re-runs its own authorization on every hop, so the shell adds reach, never authority.
 *
 * <p>No client-side span: the member's own pipeline opens the authoritative span for the hop,
 * and a second span on the calling side would double every shell navigation in the trace.
 */
public final class LoopbackCall {

    /**
     * One client for every loopback hop, for the life of the JVM. Connect is bounded here
     * (loopback connects instantly or refuses); the per-request bound is the caller's,
     * mandatory, in {@link #to}.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final HttpRequest.Builder request;
    private String method;
    private HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();

    private LoopbackCall(String method, String url, Duration timeout) {
        this.method = method;
        this.request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
    }

    /** A loopback request; the timeout is mandatory because no hop may run without one. */
    public static LoopbackCall to(String method, String url, Duration timeout) {
        return new LoopbackCall(method, url, timeout);
    }

    /** Forwards the caller's session cookie; a null forwards nothing. */
    public LoopbackCall cookie(String cookie) {
        return header("Cookie", cookie);
    }

    /** Forwards the caller's CSRF token; a null forwards nothing. */
    public LoopbackCall csrf(String token) {
        return header("X-CSRF-Token", token);
    }

    /** Sets a header; a null value sets nothing, so forwarding an absent header is a no-op. */
    public LoopbackCall header(String name, String value) {
        if (value != null) {
            request.header(name, value);
        }
        return this;
    }

    /** A form-encoded body (use {@link #encode} for the pairs) with its content type. */
    public LoopbackCall form(String encodedForm) {
        return body(encodedForm, "application/x-www-form-urlencoded");
    }

    /** A caller-authored body with its content type; a null body sends none. */
    public LoopbackCall body(String content, String contentType) {
        if (content != null) {
            body = HttpRequest.BodyPublishers.ofString(content);
            header("Content-Type", contentType);
        }
        return this;
    }

    /** Sends and buffers the answer. Any transport failure is one {@link Unreachable}. */
    public Response send() throws Unreachable {
        HttpResponse<String> response = exchange(HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers(), response.body());
    }

    /**
     * Sends and hands the body over as a stream — the transfer-file proxy exists because it
     * streams. The caller owns closing the stream (the edge closes what it writes; a
     * completion hook is the net for the path that never reaches the wire).
     */
    public Streaming stream() throws Unreachable {
        HttpResponse<InputStream> response = exchange(HttpResponse.BodyHandlers.ofInputStream());
        return new Streaming(response.statusCode(), response.headers(), response.body());
    }

    private <T> HttpResponse<T> exchange(HttpResponse.BodyHandler<T> handler)
            throws Unreachable {
        try {
            return CLIENT.send(request.method(method, body).build(), handler);
        } catch (IOException ex) {
            throw new Unreachable(ex.getMessage() == null ? "connection failed" : ex.getMessage(),
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unreachable("interrupted", ex);
        }
    }

    /**
     * URL-encodes parameters as {@code k=v&…}, skipping null values — the query-string and
     * form-body encoding every shell hop shared by copy.
     */
    public static String encode(Map<String, ?> params) {
        StringBuilder encoded = new StringBuilder();
        params.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        });
        return encoded.toString();
    }

    /** The member did not answer: the one transport signal, mapped by each caller's refusal. */
    public static final class Unreachable extends Exception {

        Unreachable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** A buffered answer. */
    public record Response(int status, HttpHeaders headers, String body) {

        /** The first value of a response header (wire names arrive in any case). */
        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }

    /** A streamed answer; the caller owns closing the body. */
    public record Streaming(int status, HttpHeaders headers, InputStream body) {

        /** The first value of a response header (wire names arrive in any case). */
        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }
}
