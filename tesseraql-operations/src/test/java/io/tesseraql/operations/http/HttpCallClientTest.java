package io.tesseraql.operations.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.telemetry.NoopTracer;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.http.HttpOutbound;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpCallClientTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Headers lastHeaders;
    private volatile String lastMethod;
    private volatile String lastQuery;
    private volatile String lastBody;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"ok\":true}";
    private volatile String responseContentType = "application/json";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastMethod = exchange.getRequestMethod();
            lastQuery = exchange.getRequestURI().getQuery();
            lastHeaders = exchange.getRequestHeaders();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody == null
                    ? new byte[0]
                    : responseBody.getBytes(StandardCharsets.UTF_8);
            if (responseContentType != null) {
                exchange.getResponseHeaders().add("Content-Type", responseContentType);
            }
            exchange.sendResponseHeaders(responseStatus, body.length == 0 ? -1 : body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AppConfig config(Map<String, Object> outbound) {
        return new AppConfig(
                Map.of("tesseraql", Map.of("http", Map.of("outbound", outbound))), name -> null);
    }

    private final io.tesseraql.core.telemetry.AggregatingMeter meter = new io.tesseraql.core.telemetry.AggregatingMeter();

    private HttpCallClient client(Map<String, Object> outbound) {
        AppConfig config = config(outbound);
        return new HttpCallClient(HttpOutbound.load(config), config, NoopTracer.INSTANCE, meter);
    }

    private HttpCallSpec call(String method, String path) {
        return new HttpCallSpec(method, "http://localhost:" + port + path,
                Map.of(), Map.of(), null, null, null, null, null);
    }

    @Test
    void issuesACallApplyingMethodHeadersQueryBodyAndCredential() {
        HttpCallClient client = client(Map.of(
                "allowedHosts", List.of("localhost"),
                "credentials", Map.of("partner", Map.of("type", "bearer", "token", "t0p"))));
        Map<String, Object> context = Map.of(
                "params", Map.of("date", "2026-06-14"),
                "steps", Map.of("prev", Map.of("id", 7)));
        HttpCallSpec spec = new HttpCallSpec("POST", "http://localhost:" + port + "/orders",
                Map.of("X-Source", "tesseraql"), Map.of("date", "params.date"), "partner",
                "steps.prev", 200, null, null);

        Map<String, Object> result = client.call(spec, context, null);

        assertThat(result).containsEntry("status", 200);
        assertThat(result.get("body")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.get("body");
        assertThat(body).containsEntry("ok", true);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastQuery).isEqualTo("date=2026-06-14");
        assertThat(lastHeaders.getFirst("X-Source")).isEqualTo("tesseraql");
        assertThat(lastHeaders.getFirst("Authorization")).isEqualTo("Bearer t0p");
        assertThat(lastBody).isEqualTo("{\"id\":7}");
    }

    @Test
    void deniesAHostOutsideTheAllowList() {
        HttpCallClient client = client(Map.of("allowedHosts", List.of("api.partner.example")));

        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-BATCH-5305");
        assertThat(hits.get()).isZero();
        // The denial rides the meter for the fleet view (docs/poll-source-metrics.md).
        assertThat(meter.counterSnapshot().get("tesseraql.egress.denied"))
                .anySatisfy(sample -> {
                    assertThat(sample.attributes()).containsEntry("host", "localhost");
                    assertThat(sample.value()).isEqualTo(1);
                });
    }

    /**
     * The raw form returns the response whatever its status — SCIM reads meaning out of a
     * 404 and a token endpoint's error body is an answer — while the allow-list still
     * applies (docs/duplication-consolidation.md, campaign 1).
     */
    @Test
    void exchangeReturnsTheResponseWhateverItsStatus() {
        HttpCallClient client = client(Map.of("allowedHosts", List.of("localhost")));
        responseStatus = 404;
        responseBody = "{\"detail\":\"gone\"}";

        io.tesseraql.yaml.http.OutboundGateway.RawResponse response = client.exchange(
                call("GET", "/users/9"), null, Map.of("Accept", "application/scim+json"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("gone");
        assertThat(response.header("Content-Type")).contains("application/json");
        assertThat(lastHeaders.getFirst("Accept")).isEqualTo("application/scim+json");
    }

    @Test
    void exchangeStillAppliesTheAllowListAndTheRefusalNamesTheFix() {
        HttpCallClient client = client(Map.of("allowedHosts", List.of("api.partner.example")));

        assertThatThrownBy(() -> client.exchange(call("GET", "/x"), null, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-BATCH-5305")
                // The refusal spells the configuration to add, the copilot gate's precedent.
                .hasMessageContaining("allowedHosts")
                .hasMessageContaining("- localhost");
        assertThat(hits.get()).isZero();
    }

    @Test
    void exchangeSendsACallerAuthoredBody() {
        HttpCallClient client = client(Map.of("allowedHosts", List.of("localhost")));

        client.exchange(call("PUT", "/users/9"),
                "{\"userName\":\"a\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/scim+json"));

        assertThat(lastMethod).isEqualTo("PUT");
        assertThat(lastBody).isEqualTo("{\"userName\":\"a\"}");
        assertThat(lastHeaders.getFirst("Content-Type")).isEqualTo("application/scim+json");
    }

    /**
     * The regression the deleted per-module clients each carried on their own: the shared
     * client must set a {@link java.net.ProxySelector}, because without one the JDK client
     * ignores even {@code http(s).proxyHost} and cannot reach a provider behind a proxy.
     */
    @Test
    void theSharedClientHonorsTheJvmProxySelector() throws Exception {
        HttpCallClient client = client(Map.of("allowedHosts", List.of("localhost")));
        java.lang.reflect.Method factory = HttpCallClient.class
                .getDeclaredMethod("client", java.time.Duration.class);
        factory.setAccessible(true);
        java.net.http.HttpClient http = (java.net.http.HttpClient) factory
                .invoke(client, java.time.Duration.ofSeconds(5));
        assertThat(http.proxy()).contains(java.net.ProxySelector.getDefault());
    }

    /**
     * Every failure leaves on the span — the status refusal, and unchecked failures alike.
     * A span that never records its exception has an unreachable error branch (the
     * Completion defect class, docs/vertx-native.md).
     */
    @Test
    void aFailedCallRecordsItsErrorOnTheSpan() {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        AppConfig config = config(Map.of("allowedHosts", List.of("localhost")));
        HttpCallClient traced = new HttpCallClient(HttpOutbound.load(config), config, tracer,
                meter);
        responseStatus = 500;

        assertThatThrownBy(() -> traced.call(call("GET", "/x"), Map.of(), null))
                .isInstanceOf(TqlException.class);

        assertThat(tracer.spans()).hasSize(1);
        assertThat(tracer.spans().get(0).error()).isTrue();
    }

    /** An unknown credential is an unchecked failure inside the span; it must not end clean. */
    @Test
    void anUncheckedFailureRecordsOnTheSpanToo() {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        AppConfig config = config(Map.of("allowedHosts", List.of("localhost")));
        HttpCallClient traced = new HttpCallClient(HttpOutbound.load(config), config, tracer,
                meter);
        HttpCallSpec withCredential = new HttpCallSpec("GET", "http://localhost:" + port + "/x",
                Map.of(), Map.of(), "missing-credential", null, null, null, null);

        assertThatThrownBy(() -> traced.call(withCredential, Map.of(), null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("missing-credential");

        assertThat(tracer.spans()).hasSize(1);
        assertThat(tracer.spans().get(0).error()).isTrue();
    }

    @Test
    void anAllowedCallDoesNotCountAnEgressDenial() {
        HttpCallClient client = client(Map.of("allowedHosts", List.of("localhost")));

        client.call(call("GET", "/x"), Map.of(), null);

        assertThat(meter.counterSnapshot()).doesNotContainKey("tesseraql.egress.denied");
    }

    @Test
    void failsTheStepOnAServerError() {
        responseStatus = 500;
        HttpCallClient client = client(Map.of("allowedHosts", List.of("localhost")));

        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-BATCH-5307");
    }

    @Test
    void failsTheStepOnAnExpectStatusMismatchWithoutTrippingTheBreaker() {
        responseStatus = 200;
        HttpCallClient client = client(Map.of(
                "allowedHosts", List.of("localhost"),
                "circuitBreaker", Map.of("failureThreshold", 1)));
        HttpCallSpec spec = new HttpCallSpec("GET", "http://localhost:" + port + "/x",
                Map.of(), Map.of(), null, null, 201, null, null);

        // A 200 where 201 was required fails the step (deterministic) but is not systemic...
        assertThatThrownBy(() -> client.call(spec, Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5307");
        // ...so the breaker stays closed and the next call still reaches the server.
        assertThatThrownBy(() -> client.call(spec, Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5307");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void opensThePerHostCircuitAfterConsecutiveServerErrors() {
        responseStatus = 500;
        HttpCallClient client = client(Map.of(
                "allowedHosts", List.of("localhost"),
                "circuitBreaker", Map.of("failureThreshold", 2, "openDuration", "60s")));

        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5307");
        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5307");
        int hitsBeforeOpen = hits.get();

        // The breaker is now open: the call fails fast without reaching the server.
        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5306");
        assertThat(hits.get()).isEqualTo(hitsBeforeOpen);
    }

    @Test
    void halfOpensTheCircuitAfterTheCooldown() {
        AtomicLong now = new AtomicLong(1_000);
        AppConfig config = config(Map.of(
                "allowedHosts", List.of("localhost"),
                "circuitBreaker", Map.of("failureThreshold", 1, "openDuration", "30s")));
        HttpCallClient client = new HttpCallClient(
                HttpOutbound.load(config), config, NoopTracer.INSTANCE, meter, now::get);

        responseStatus = 500;
        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5307");
        assertThatThrownBy(() -> client.call(call("GET", "/x"), Map.of(), null))
                .hasMessageContaining("TQL-BATCH-5306");

        // Past the cooldown the breaker half-opens and a recovered dependency closes it again.
        now.set(1_000 + 30_001);
        responseStatus = 200;
        responseBody = "{\"ok\":true}";
        Map<String, Object> result = client.call(call("GET", "/x"), Map.of(), null);
        assertThat(result).containsEntry("status", 200);
    }

    @Test
    void returnsANonJsonBodyAsText() {
        responseContentType = "text/plain";
        responseBody = "pong";
        HttpCallClient client = client(Map.of("allowedHosts", List.of("localhost")));

        Map<String, Object> result = client.call(call("GET", "/ping"), Map.of(), null);

        assertThat(result).containsEntry("status", 200);
        assertThat(result.get("body")).isEqualTo("pong");
        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastBody).isEmpty();
    }
}
