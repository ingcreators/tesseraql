package io.tesseraql.operations.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.telemetry.Meter;
import io.tesseraql.core.telemetry.NoopMeter;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.SpanContext;
import io.tesseraql.core.telemetry.Tracer;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.http.HttpOutbound;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Executes an {@code http-call} pipeline step (roadmap Phase 26): one synchronous outbound REST
 * request under the {@link HttpOutbound} policy, recorded in a trace span.
 *
 * <p>Egress is deny-by-default: the resolved target host must be allow-listed, otherwise the call
 * never leaves the process (defense in depth — the same rule lint enforces statically). Credentials,
 * timeouts, and the circuit breaker come from configuration so a step never carries a secret. The
 * response is published to later steps as {@code steps.<id>.status}, {@code steps.<id>.body} (parsed
 * JSON when the response is JSON, else the raw text), and {@code steps.<id>.headers}.
 *
 * <p>A per-host circuit breaker trips after a threshold of consecutive <em>systemic</em> failures —
 * transport errors and {@code 5xx} responses — and stays open for the configured duration, failing
 * fast (rather than hammering a struggling dependency) until a half-open trial succeeds. A {@code 4xx}
 * or an {@code expectStatus} mismatch fails the step but does not trip the breaker: it is a
 * deterministic rejection, not a sign the dependency is down.
 */
public final class HttpCallClient implements io.tesseraql.yaml.http.OutboundGateway {

    // The gateway's classification codes are declared beside the policy (HttpOutbound), so a
    // gateway caller can tell a configuration refusal from a transient failure; these are the
    // local aliases.
    private static final TqlErrorCode HOST_DENIED = HttpOutbound.HOST_DENIED;
    private static final TqlErrorCode CIRCUIT_OPEN = HttpOutbound.CIRCUIT_OPEN;
    private static final TqlErrorCode CALL_FAILED = HttpOutbound.CALL_FAILED;
    private static final TqlErrorCode INVALID_CALL = HttpOutbound.INVALID_CALL;

    private final HttpOutbound outbound;
    private final AppConfig config;
    private final Tracer tracer;
    private final Meter meter;
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
    private final LongSupplier clock;
    private final Map<Long, HttpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public HttpCallClient(HttpOutbound outbound, AppConfig config, Tracer tracer, Meter meter) {
        this(outbound, config, tracer, meter, System::currentTimeMillis);
    }

    HttpCallClient(HttpOutbound outbound, AppConfig config, Tracer tracer, Meter meter,
            LongSupplier clock) {
        this.outbound = outbound;
        this.config = config;
        this.tracer = tracer;
        this.meter = meter == null ? NoopMeter.INSTANCE : meter;
        this.clock = clock;
    }

    /** Issues the call and returns the {@code status}/{@code body}/{@code headers} step result. */
    public Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context,
            SpanContext parent) {
        return call(spec, context, parent, null, Map.of());
    }

    /** The {@code OutboundGateway} form: the same call, parented to the current trace root. */
    @Override
    public Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context) {
        return call(spec, context, null);
    }

    /**
     * The same call with the body bytes and some headers supplied by the caller — a signed
     * delivery, whose HMAC covers the exact bytes on the wire, cannot let a second
     * serialization here decide them (docs/lookups.md, decision 20). Everything else the
     * gateway is for — the allow-list, the credential, the timeouts, the circuit breaker, the
     * span and the meters — applies unchanged.
     */
    @Override
    public Map<String, Object> call(HttpCallSpec spec, byte[] body,
            Map<String, String> headers) {
        return call(spec, Map.of(), null, body, headers);
    }

    /**
     * The raw form of the seam (docs/duplication-consolidation.md, campaign 1): the same
     * admission — allow-list, circuit breaker — the same timeouts, the same span, but the
     * response returns as it arrived, whatever its status. A {@code 5xx} still counts against
     * the breaker and a transport failure is still classified {@code TQL-BATCH-5307}; what a
     * status <em>means</em> stays with the caller, because SCIM reads meaning out of a 404
     * and a token endpoint's error body is an answer, not a fault.
     */
    @Override
    public io.tesseraql.yaml.http.OutboundGateway.RawResponse exchange(HttpCallSpec spec,
            byte[] body, Map<String, String> headers) {
        URI uri = admit(spec, Map.of());
        String host = uri.getHost();
        Breaker breaker = admittedBreaker(host);
        Span span = tracer.start("tesseraql.http.call", null)
                .attribute("method", spec.effectiveMethod())
                .attribute("host", host);
        try {
            HttpResponse<byte[]> response = send(spec, uri, Map.of(), body,
                    headers == null ? Map.of() : headers);
            int status = response.statusCode();
            span.attribute("status", status);
            if (status >= 500) {
                breaker.recordFailure(clock.getAsLong(), outbound.circuitBreakerThreshold(),
                        openDuration());
            } else {
                breaker.recordSuccess();
            }
            return new io.tesseraql.yaml.http.OutboundGateway.RawResponse(status,
                    response.body(), response.headers().map());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            breaker.recordFailure(clock.getAsLong(), outbound.circuitBreakerThreshold(),
                    openDuration());
            TqlException failure = new TqlException(CALL_FAILED, "http-call to '" + host
                    + "' failed: " + ex.getMessage(), ex);
            span.recordError(failure);
            throw failure;
        } catch (RuntimeException | Error ex) {
            // An unchecked failure must not end the span clean — the Completion defect class
            // (docs/vertx-native.md): a span that never records its exception has an
            // unreachable error branch. Not a remote failure, so the breaker stays untouched.
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    private Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context,
            SpanContext parent, byte[] rawBody, Map<String, String> extraHeaders) {
        URI uri = admit(spec, context);
        String host = uri.getHost();
        Breaker breaker = admittedBreaker(host);

        Span span = tracer.start("tesseraql.http.call", parent)
                .attribute("method", spec.effectiveMethod())
                .attribute("host", host);
        try {
            HttpResponse<byte[]> response = send(spec, uri, context, rawBody, extraHeaders);
            int status = response.statusCode();
            span.attribute("status", status);
            boolean success = spec.expectStatus() != null
                    ? status == spec.expectStatus()
                    : status / 100 == 2;
            if (!success) {
                // 5xx is systemic (trip the breaker); 4xx / expectStatus mismatch is a
                // deterministic rejection (fail the step, leave the breaker closed).
                if (status >= 500) {
                    breaker.recordFailure(clock.getAsLong(), outbound.circuitBreakerThreshold(),
                            openDuration());
                }
                throw new TqlException(CALL_FAILED, "http-call to '" + host
                        + "' returned HTTP " + status);
            }
            breaker.recordSuccess();
            return result(status, response);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            breaker.recordFailure(clock.getAsLong(), outbound.circuitBreakerThreshold(),
                    openDuration());
            TqlException failure = new TqlException(CALL_FAILED, "http-call to '" + host
                    + "' failed: " + ex.getMessage(), ex);
            span.recordError(failure);
            throw failure;
        } catch (RuntimeException | Error ex) {
            // Every failure leaves on the span — the status refusal above, an unknown
            // credential, a body that will not serialize, or a plain bug. A span that never
            // records its exception has an unreachable error branch (the Completion defect
            // class, docs/vertx-native.md).
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /** Validates the target and applies the allow-list; the admission every form shares. */
    private URI admit(HttpCallSpec spec, Map<String, Object> context) {
        String url = buildUrl(spec, context);
        URI uri = URI.create(url);
        String host = uri.getHost();
        String scheme = uri.getScheme();
        if (host == null || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new TqlException(INVALID_CALL, "http-call url '" + spec.url()
                    + "' must be an absolute http or https URL");
        }
        if (!outbound.isHostAllowed(host)) {
            // The denial is loud per-execution; the counter adds the fleet view — a rate
            // of denials after a config rollout is the alertable regression
            // (docs/poll-source-metrics.md).
            meter.counter("tesseraql.egress.denied").increment(Map.of("host", host));
            throw new TqlException(HOST_DENIED, "Outbound host '" + host
                    + "' is not in tesseraql.http.outbound.allowedHosts (egress is deny by"
                    + " default); allow it:\n"
                    + "tesseraql:\n"
                    + "  http:\n"
                    + "    outbound:\n"
                    + "      allowedHosts:\n"
                    + "        - " + host);
        }
        return uri;
    }

    /** The per-host breaker, refusing when its circuit is open. */
    private Breaker admittedBreaker(String host) {
        Breaker breaker = breakers.computeIfAbsent(host, h -> new Breaker());
        if (breaker.isOpen(clock.getAsLong())) {
            throw new TqlException(CIRCUIT_OPEN, "http-call circuit for host '" + host
                    + "' is open after repeated failures");
        }
        return breaker;
    }

    private HttpResponse<byte[]> send(HttpCallSpec spec, URI uri, Map<String, Object> context,
            byte[] rawBody, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        Duration requestTimeout = spec.requestTimeout() != null
                ? io.tesseraql.core.util.Durations.parse(spec.requestTimeout())
                : outbound.requestTimeout();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        // Static headers may carry ${...} config or secret placeholders, resolved on send.
        spec.headers().forEach((name, value) -> request.header(name, config.resolve(value)));
        applyCredential(spec, request);

        extraHeaders.forEach(request::header);
        String method = spec.effectiveMethod();
        HttpRequest.BodyPublisher publisher = rawBody != null
                ? HttpRequest.BodyPublishers.ofByteArray(rawBody)
                : bodyPublisher(spec, context, request);
        request.method(method, publisher);

        Duration connectTimeout = spec.connectTimeout() != null
                ? io.tesseraql.core.util.Durations.parse(spec.connectTimeout())
                : outbound.connectTimeout();
        return client(connectTimeout).send(request.build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpCallSpec spec, Map<String, Object> context,
            HttpRequest.Builder request) {
        if (spec.body() == null || spec.body().isBlank()) {
            return HttpRequest.BodyPublishers.noBody();
        }
        Object value = new EvaluationContext(context)
                .resolve(Arrays.asList(spec.body().split("\\.")));
        if (value == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new TqlException(INVALID_CALL, "http-call body '" + spec.body()
                    + "' is not serializable to JSON", ex);
        }
        // Only set a default content type when the step did not declare one itself.
        if (spec.headers().keySet().stream().noneMatch(h -> h.equalsIgnoreCase("Content-Type"))) {
            request.header("Content-Type", "application/json; charset=utf-8");
        }
        return HttpRequest.BodyPublishers.ofByteArray(bytes);
    }

    private void applyCredential(HttpCallSpec spec, HttpRequest.Builder request) {
        if (spec.credential() == null || spec.credential().isBlank()) {
            return;
        }
        outbound.requireCredential(spec.credential()).authorizationHeaders()
                .forEach(request::header);
    }

    private String buildUrl(HttpCallSpec spec, Map<String, Object> context) {
        String base = config.resolve(spec.url());
        if (spec.query().isEmpty()) {
            return base;
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        List<String> pairs = new ArrayList<>();
        spec.query().forEach((name, sourceExpr) -> {
            Object value = evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")));
            if (value != null) {
                pairs.add(encode(name) + "=" + encode(String.valueOf(value)));
            }
        });
        if (pairs.isEmpty()) {
            return base;
        }
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + String.join("&", pairs);
    }

    private Map<String, Object> result(int status, HttpResponse<byte[]> response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("body", parseBody(response));
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        result.put("headers", headers);
        return result;
    }

    private Object parseBody(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return null;
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
        String text = new String(body, StandardCharsets.UTF_8);
        if (contentType.contains("json")) {
            try {
                return mapper.readValue(body, Object.class);
            } catch (IOException ex) {
                // A malformed JSON body is surfaced as text rather than failing the whole step.
                return text;
            }
        }
        return text;
    }

    private HttpClient client(Duration connectTimeout) {
        return clients.computeIfAbsent(connectTimeout.toMillis(), millis -> HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Honor the JVM proxy configuration; without it the JDK client ignores proxy props.
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    private long openDuration() {
        return outbound.circuitBreakerOpenDuration().toMillis();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A per-host failure counter that opens for a cooldown once a threshold is reached. */
    private static final class Breaker {

        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long openUntil;

        boolean isOpen(long now) {
            return openUntil > now;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            openUntil = 0;
        }

        void recordFailure(long now, int threshold, long openMillis) {
            if (consecutiveFailures.incrementAndGet() >= threshold) {
                openUntil = now + openMillis;
            }
        }
    }
}
