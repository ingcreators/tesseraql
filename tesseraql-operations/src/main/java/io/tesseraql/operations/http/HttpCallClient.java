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
 *
 * <p>A binding that declares {@code retry:} repeats exactly those systemic failures, with a
 * growing backoff, while the policy still has an attempt and the remaining budget can hold
 * another whole request timeout. Every repeated attempt counts against the breaker, and if that
 * opens the host's circuit — this call's failures, or another caller's — the sequence ends at
 * once as {@code TQL-BATCH-5306}: continuing past an open circuit is the hammering the breaker
 * exists to stop. The span carries how many attempts were made, so a retried call meters
 * honestly rather than looking like one lucky request.
 *
 * <p>The breaker is keyed by host alone and shared by every surface behind the one gateway bean —
 * a job's {@code http-call} step, SCIM provisioning, OIDC, JWKS, SAML metadata. Repeated failures
 * toward a host fail every caller of that host fast, deliberately: the host is down for all of
 * them alike, and hammering it from a second surface would not make it healthier.
 */
public final class HttpCallClient implements io.tesseraql.yaml.http.OutboundGateway {

    // The gateway's classification codes are declared beside the policy (HttpOutbound), so a
    // gateway caller can tell a configuration refusal from a transient failure; these are the
    // local aliases.
    private static final TqlErrorCode HOST_DENIED = HttpOutbound.HOST_DENIED;
    private static final TqlErrorCode CIRCUIT_OPEN = HttpOutbound.CIRCUIT_OPEN;
    private static final TqlErrorCode CALL_FAILED = HttpOutbound.CALL_FAILED;
    private static final TqlErrorCode INVALID_CALL = HttpOutbound.INVALID_CALL;
    private static final TqlErrorCode RESPONSE_TOO_LARGE = HttpOutbound.RESPONSE_TOO_LARGE;

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

    /**
     * The {@code OutboundGateway} form: the same call. With no caller-supplied parent and no
     * ambient current span to draw on, the span starts a trace of its own — a caller holding a
     * live span uses the parented overload instead.
     */
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
     * and a token endpoint's error body is an answer, not a fault. For the breaker, a
     * {@code 4xx} counts as neither success nor failure — the deterministic-rejection stance
     * {@code call()} has always taken — so both forms account a host's health identically.
     */
    @Override
    public io.tesseraql.yaml.http.OutboundGateway.RawResponse exchange(HttpCallSpec spec,
            byte[] body, Map<String, String> headers) {
        // The span opens before admission, so a refusal — denied host, open circuit — is a
        // recorded trace and not a call that never happened.
        Span span = tracer.start("tesseraql.http.call", null)
                .attribute("method", spec.effectiveMethod());
        try {
            URI uri = admit(spec, Map.of());
            String host = uri.getHost();
            span.attribute("host", host);
            Breaker breaker = admittedBreaker(host);
            try {
                HttpResponse<byte[]> response = sendWithRetry(spec, uri, host, breaker, span,
                        Map.of(), body, headers == null ? Map.of() : headers);
                int status = response.statusCode();
                span.attribute("status", status);
                // 5xx is systemic; 2xx/3xx proves the host healthy; a 4xx says neither. It
                // used to reset the counter here, so a host alternating 500 and 404 could
                // never trip the breaker through this form while tripping it through call().
                if (status >= 500) {
                    breaker.recordFailure(clock.getAsLong(), outbound.circuitBreakerThreshold(),
                            openDuration());
                } else if (status < 400) {
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
                throw new TqlException(CALL_FAILED, "http-call to '" + host
                        + "' failed: " + ex.getMessage(), ex);
            }
        } catch (RuntimeException | Error ex) {
            // Every failure leaves on the span — an admission refusal, the wrapped transport
            // failure, or a plain bug. A span that never records its exception has an
            // unreachable error branch (the Completion defect class, docs/vertx-native.md).
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    private Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context,
            SpanContext parent, byte[] rawBody, Map<String, String> extraHeaders) {
        // The span opens before admission, so a refusal — denied host, open circuit — is a
        // recorded trace and not a call that never happened.
        Span span = tracer.start("tesseraql.http.call", parent)
                .attribute("method", spec.effectiveMethod());
        try {
            URI uri = admit(spec, context);
            String host = uri.getHost();
            span.attribute("host", host);
            Breaker breaker = admittedBreaker(host);
            try {
                HttpResponse<byte[]> response = sendWithRetry(spec, uri, host, breaker, span,
                        context, rawBody, extraHeaders);
                int status = response.statusCode();
                span.attribute("status", status);
                boolean success = spec.expectStatus() != null
                        ? status == spec.expectStatus()
                        : status / 100 == 2;
                if (!success) {
                    // 5xx is systemic (trip the breaker); 4xx / expectStatus mismatch is a
                    // deterministic rejection (fail the step, leave the breaker closed).
                    if (status >= 500) {
                        breaker.recordFailure(clock.getAsLong(),
                                outbound.circuitBreakerThreshold(), openDuration());
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
                throw new TqlException(CALL_FAILED, "http-call to '" + host
                        + "' failed: " + ex.getMessage(), ex);
            }
        } catch (RuntimeException | Error ex) {
            // Every failure leaves on the span — an admission refusal, the status refusal, an
            // unknown credential, the wrapped transport failure, or a plain bug. A span that
            // never records its exception has an unreachable error branch (the Completion
            // defect class, docs/vertx-native.md).
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

    /**
     * The send, repeated while the failure is systemic and the policy still has an attempt for
     * it (docs/connectors.md, "Retry"). Opt-in: a spec with no {@code retry:} resolves to one
     * attempt and this is the bare send.
     *
     * <p>Retried: a transport failure, a timeout, a {@code 5xx}. Never retried: a {@code 4xx} or
     * a declared {@code expectStatus} — deterministic rejections, the same line the circuit
     * breaker already draws — nor a response over the policy's size ceiling, which is a bound
     * the provider will hit again. An interrupt is not the dependency's fault and ends the call
     * at once.
     *
     * <p>Two bounds beyond {@code attempts}. Each repeated attempt counts against the host's
     * breaker before the next one goes out, and an open circuit ends the sequence there and then
     * — the failure that is counted is the one the caller then sees, so the accounting stays one
     * count per request that actually left. And the whole sequence lives inside a budget of
     * {@code attempts × requestTimeout}, which the backoff waits spend too, so a retry that
     * cannot fit another full request is not started rather than running long past what the
     * binding's own timeout led its caller to expect.
     */
    private HttpResponse<byte[]> sendWithRetry(HttpCallSpec spec, URI uri, String host,
            Breaker breaker, Span span, Map<String, Object> context, byte[] rawBody,
            Map<String, String> extraHeaders) throws IOException, InterruptedException {
        Retry retry = retryFor(spec);
        if (retry.attempts() == 1) {
            return send(spec, uri, context, rawBody, extraHeaders);
        }
        long perAttempt = requestTimeout(spec).toMillis();
        long deadline = clock.getAsLong() + perAttempt * retry.attempts();
        long wait = retry.backoffMillis();
        for (int attempt = 1;; attempt++) {
            IOException failure = null;
            HttpResponse<byte[]> response = null;
            try {
                response = send(spec, uri, context, rawBody, extraHeaders);
                if (!isSystemic(spec, response.statusCode())) {
                    span.attribute("attempts", attempt);
                    return response;
                }
            } catch (IOException ex) {
                failure = ex;
            }
            if (attempt >= retry.attempts() || !fits(deadline, wait, perAttempt)) {
                // Out of attempts, or the budget cannot hold another: this outcome is the
                // call's, and the caller classifies and counts it as it would an unretried one.
                span.attribute("attempts", attempt);
                if (failure != null) {
                    throw failure;
                }
                return response;
            }
            // This attempt is being repeated, so it counts against the host now.
            breaker.recordFailure(clock.getAsLong(), outbound.circuitBreakerThreshold(),
                    openDuration());
            if (breaker.isOpen(clock.getAsLong())) {
                span.attribute("attempts", attempt);
                throw new TqlException(CIRCUIT_OPEN, "http-call circuit for host '" + host
                        + "' opened while retrying after repeated failures");
            }
            meter.counter("tesseraql.http.retries").increment(Map.of("host", host));
            Thread.sleep(wait);
            wait = (long) Math.min(wait * retry.multiplier(), perAttempt * (long) retry.attempts());
        }
    }

    /**
     * Whether this status is the systemic failure a retry is for. A {@code 5xx} is, unless the
     * binding declared it as its success — an {@code expectStatus} names the answer the caller
     * wants, and repeating the answer it asked for would be absurd.
     */
    private static boolean isSystemic(HttpCallSpec spec, int status) {
        return status >= 500
                && (spec.expectStatus() == null || spec.expectStatus() != status);
    }

    /** Whether the backoff and one more whole request still fit inside the sequence's budget. */
    private boolean fits(long deadline, long wait, long perAttempt) {
        return clock.getAsLong() + wait + perAttempt <= deadline;
    }

    /** One call's resolved retry policy; a binding that declared none resolves to one attempt. */
    private record Retry(int attempts, long backoffMillis, double multiplier) {
    }

    /**
     * The binding's {@code retry:} over the {@code tesseraql.http.outbound.retry} numbers.
     * Absent means one attempt: retry changes the load a declaration puts on its dependency, so
     * it is asked for, never configured onto callers who did not.
     */
    private Retry retryFor(HttpCallSpec spec) {
        io.tesseraql.yaml.model.RetrySpec declared = spec.retry();
        if (declared == null) {
            return new Retry(1, 0, 1);
        }
        int attempts = declared.attempts() != null
                ? declared.attempts()
                : outbound.retryAttempts();
        attempts = Math.max(1, Math.min(io.tesseraql.yaml.model.RetrySpec.MAX_ATTEMPTS, attempts));
        long backoff = declared.backoff() != null && !declared.backoff().isBlank()
                ? io.tesseraql.core.util.Durations.toMillis(declared.backoff())
                : outbound.retryBackoff().toMillis();
        double multiplier = declared.multiplier() != null
                ? declared.multiplier()
                : outbound.retryMultiplier();
        return new Retry(attempts, Math.max(0, backoff), Math.max(1, multiplier));
    }

    /** This call's request timeout: the binding's override, else the configured default. */
    private Duration requestTimeout(HttpCallSpec spec) {
        return spec.requestTimeout() != null
                ? io.tesseraql.core.util.Durations.parse(spec.requestTimeout())
                : outbound.requestTimeout();
    }

    private HttpResponse<byte[]> send(HttpCallSpec spec, URI uri, Map<String, Object> context,
            byte[] rawBody, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout(spec));
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
        try {
            return client(connectTimeout).send(request.build(),
                    new BoundedBody(outbound.maxResponseBytes()));
        } catch (IOException ex) {
            BoundedBody.TooLarge tooLarge = BoundedBody.tooLargeIn(ex);
            if (tooLarge == null) {
                throw ex;
            }
            // A policy bound, not a transport failure: it must not classify TQL-BATCH-5307
            // or count against the breaker — the host answered, just too fully.
            throw new TqlException(RESPONSE_TOO_LARGE, "http-call response from '"
                    + uri.getHost() + "' exceeded tesseraql.http.outbound.maxResponseBytes ("
                    + tooLarge.maxBytes() + " bytes); raise it, or have the provider answer"
                    + " smaller");
        }
    }

    /**
     * {@code ofByteArray} under the response ceiling ({@code -1} passes through unbounded): a
     * declared {@code Content-Length} over the bound refuses before a byte is buffered, and a
     * chunked or lying stream is counted and cancelled the moment it crosses the bound — the
     * refusal must not require the allocation it exists to prevent.
     */
    private static final class BoundedBody
            implements
                HttpResponse.BodyHandler<byte[]> {

        /** The refusal that cancels the stream; surfaces as the send's {@code IOException} cause. */
        static final class TooLarge extends IOException {

            private static final long serialVersionUID = 1L;

            private final long maxBytes;

            TooLarge(long maxBytes) {
                super("response exceeded " + maxBytes + " bytes");
                this.maxBytes = maxBytes;
            }

            long maxBytes() {
                return maxBytes;
            }
        }

        private final long maxBytes;

        BoundedBody(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** The {@link TooLarge} in {@code ex}'s cause chain, or null. */
        static TooLarge tooLargeIn(Throwable ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof TooLarge tooLarge) {
                    return tooLarge;
                }
            }
            return null;
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
            if (maxBytes < 0) {
                return HttpResponse.BodySubscribers.ofByteArray();
            }
            long declared = info.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) {
                return refusing();
            }
            return counting();
        }

        /** Refuses on the declared length alone; the connection is cancelled unread. */
        private HttpResponse.BodySubscriber<byte[]> refusing() {
            return new HttpResponse.BodySubscriber<>() {
                @Override
                public java.util.concurrent.CompletionStage<byte[]> getBody() {
                    return java.util.concurrent.CompletableFuture
                            .failedFuture(new TooLarge(maxBytes));
                }

                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                    subscription.cancel();
                }

                @Override
                public void onNext(List<java.nio.ByteBuffer> item) {
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onComplete() {
                }
            };
        }

        /** Counts an undeclared (chunked, or lying) body and cancels past the bound. */
        private HttpResponse.BodySubscriber<byte[]> counting() {
            HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers
                    .ofByteArray();
            return new HttpResponse.BodySubscriber<>() {
                private java.util.concurrent.Flow.Subscription subscription;
                private long received;
                private boolean refused;

                @Override
                public java.util.concurrent.CompletionStage<byte[]> getBody() {
                    return delegate.getBody();
                }

                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    this.subscription = s;
                    delegate.onSubscribe(s);
                }

                @Override
                public void onNext(List<java.nio.ByteBuffer> item) {
                    if (refused) {
                        return;
                    }
                    for (java.nio.ByteBuffer buffer : item) {
                        received += buffer.remaining();
                    }
                    if (received > maxBytes) {
                        refused = true;
                        subscription.cancel();
                        delegate.onError(new TooLarge(maxBytes));
                        return;
                    }
                    delegate.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    if (!refused) {
                        delegate.onError(throwable);
                    }
                }

                @Override
                public void onComplete() {
                    if (!refused) {
                        delegate.onComplete();
                    }
                }
            };
        }
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
