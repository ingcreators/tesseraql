package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The gateway's hop, against an origin a test can make misbehave on demand.
 *
 * <p>{@link MultiAppGatewayDifferentialTest} proves a real application answers the same through
 * the gateway as beside it. These are the cases that need an origin under the test's control
 * rather than a hosted application: an event stream on a known schedule, a header the app would
 * have to echo to be observed, a body with no declared length. Decision 13 of
 * docs/suite-architecture.md called the first of these out as the one thing that genuinely needed
 * measuring, because buffering there produces "working, but late" — the hardest failure to
 * diagnose.
 */
class SuiteRelayTest {

    private static final int EVENTS = 5;
    private static final long GAP_MILLIS = 300;
    private static final String APP = "stub";

    private static Vertx vertx;
    private static HttpClient client;
    private static HttpServer origin;
    private static HttpServer front;
    private static String base;
    /** What the origin actually received, so ingress stripping is observable on the wire. */
    private static volatile Map<String, String> lastRequestHeaders = Map.of();

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();

        origin = vertx.createHttpServer(new HttpServerOptions().setPort(0));
        origin.requestHandler(SuiteRelayTest::serveStub);
        int originPort = await(origin.listen()).actualPort();

        SuiteRelay relay = new SuiteRelay(client, MultiAppGateway.Mode.SUITE, Map.of(),
                Map.of(), appId -> originPort);
        front = vertx.createHttpServer(SuiteRelay.frontOptions(0));
        front.requestHandler(relay::handle);
        base = "http://localhost:" + await(front.listen()).actualPort() + "/apps/" + APP;
    }

    @AfterAll
    static void stop() throws Exception {
        if (front != null) {
            await(front.close());
        }
        if (origin != null) {
            await(origin.close());
        }
        if (client != null) {
            await(client.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    /**
     * An event written by the origin reaches the client before the next one is written.
     *
     * <p>This is the measurement decision 13 asked for, kept as an assertion. The relay it
     * replaced failed it twice over: a response with no declared length was announced to
     * {@code com.sun.net.httpserver} as {@code -1}, which means "no body", so nothing arrived at
     * all; and with the length corrected the copy loop never flushed, so all five frames landed
     * together when the stream closed. MCP's Streamable HTTP, the ops console and Studio's
     * preview all ride this path.
     */
    @Test
    void eventsArriveAsTheyAreWrittenRatherThanAtTheEnd() throws Exception {
        List<Long> arrivals = readStream(base + "/sse");

        assertThat(arrivals).as("every frame arrives").hasSize(EVENTS);
        long spread = arrivals.getLast() - arrivals.getFirst();
        assertThat(spread)
                .as("first to last frame: a live stream spans the origin's schedule (~%d ms),"
                        + " a buffered one collapses to ~0", GAP_MILLIS * (EVENTS - 1))
                .isGreaterThan(GAP_MILLIS * (EVENTS - 1) / 2);
    }

    /** A body with no declared length is relayed whole rather than dropped. */
    @Test
    void aChunkedBodyWithNoDeclaredLengthIsRelayedWhole() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/chunked")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-length"))
                .as("the case is only meaningful without a declared length").isEmpty();
        assertThat(response.body()).isEqualTo("one-two-three");
    }

    /**
     * The edge's forwarded headers reach the application, and the hop's own do not.
     *
     * <p>The gateway used to strip the mTLS forwarded header an app declares. It stripped
     * unconditionally, so the value the edge had just set was destroyed with the forged one, and
     * mTLS forwarded-header authentication could not work behind the gateway at all —
     * {@code MtlsIntegrationTest} never noticed because it never goes through a gateway. The trust
     * contract sits at the edge, where docs/authentication.md already puts it: "the edge must
     * overwrite (or strip) the forwardedHeader on every inbound request, and the runtime must not
     * be reachable except through that edge."
     *
     * <p>The hop-by-hop half is asserted rather than assumed, because the gateway now relies on
     * {@code vertx-http-proxy} to drop those instead of carrying a list of its own.
     */
    @Test
    void forwardedHeadersReachTheAppAndHopByHopHeadersDoNot() throws Exception {
        send(HttpRequest.newBuilder(URI.create(base + "/echo"))
                .header("X-Client-Cert", "from-the-edge")
                .header("X-Tenant-Id", "tenant-a")
                .header("TE", "trailers")
                .header("Trailer", "X-Checksum"));

        assertThat(lastRequestHeaders)
                .as("the edge's certificate assertion is the app's to authenticate on")
                .containsEntry("x-client-cert", "from-the-edge");
        assertThat(lastRequestHeaders)
                .as("the app's own tenancy resolution is the authoritative one"
                        + " (docs/app-isolation-model.md decision 3)")
                .containsEntry("x-tenant-id", "tenant-a");
        assertThat(lastRequestHeaders)
                .as("hop-by-hop headers address this hop, and the proxy ends it")
                .doesNotContainKeys("te", "trailer", "connection", "keep-alive", "upgrade");
    }

    /** A cookie's attributes are the app's to choose; the relay does not rewrite them. */
    @Test
    void aCookiesPathIsRelayedVerbatim() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/cookie")));

        assertThat(response.headers().allValues("set-cookie"))
                .containsExactly("tesseraql_sid=abc; Path=/; HttpOnly; SameSite=Lax");
    }

    /**
     * The door imposes no body cap of its own, in either direction.
     *
     * <p>The relay it replaced read the whole request into a {@code byte[]} and refused past
     * 10 MB, and aborted a response mid-body past 64 MB — the first capped every upload in every
     * hosted app, the second silently truncated exactly the exports the export pipeline exists to
     * stream. Asserted against an origin with no limit of its own, because a hosted application
     * has one and would decide the answer instead (see
     * {@link MultiAppGatewayDifferentialTest#theAppsOwnBodyLimitGovernsRatherThanTheGateways}).
     */
    @Test
    void neitherDirectionIsCappedByTheDoor() throws Exception {
        int upload = 32 * 1024 * 1024;

        HttpResponse<String> sent = send(HttpRequest.newBuilder(URI.create(base + "/upload"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[upload])));

        assertThat(sent.statusCode()).as("past the 10 MB the door used to refuse at")
                .isEqualTo(200);
        assertThat(sent.body()).as("and every byte reached the origin")
                .isEqualTo(String.valueOf(upload));

        HttpResponse<InputStream> received = streamOf(base + "/download");
        assertThat(received.statusCode()).isEqualTo(200);
        assertThat(drain(received)).as("past the 64 MB the door used to abort the relay at")
                .isEqualTo(96L * 1024 * 1024);
    }

    /** No app answers at this address, and the relay says so rather than proxying. */
    @Test
    void anAddressNoAppAnswersIsRefusedHere() throws Exception {
        String root = base.substring(0, base.lastIndexOf("/apps/"));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(root + "/nope")));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("TQL-APP-4040");
    }

    // ---------------------------------------------------------------- the origin

    private static void serveStub(io.vertx.core.http.HttpServerRequest request) {
        java.util.Map<String, String> received = new java.util.TreeMap<>();
        request.headers().forEach(entry -> received
                .put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue()));
        lastRequestHeaders = Map.copyOf(received);

        HttpServerResponse response = request.response();
        String path = request.path();
        if (path.endsWith("/sse")) {
            response.putHeader("Content-Type", "text/event-stream").setChunked(true);
            emit(response, 0);
        } else if (path.endsWith("/chunked")) {
            response.setChunked(true);
            response.write("one-");
            vertx.setTimer(50, first -> {
                response.write("two-");
                vertx.setTimer(50, second -> response.end("three"));
            });
        } else if (path.endsWith("/upload")) {
            long[] seen = {0};
            request.handler(chunk -> seen[0] += chunk.length());
            request.endHandler(done -> response.end(String.valueOf(seen[0])));
        } else if (path.endsWith("/download")) {
            response.setChunked(true);
            writeChunks(response, 96 * 1024 * 1024 / (64 * 1024));
        } else if (path.endsWith("/cookie")) {
            response.putHeader("Set-Cookie", "tesseraql_sid=abc; Path=/; HttpOnly; SameSite=Lax")
                    .end("ok");
        } else {
            response.end("ok");
        }
    }

    /** Writes {@code remaining} 64 KB chunks, respecting the write queue rather than filling it. */
    private static void writeChunks(HttpServerResponse response, int remaining) {
        if (remaining == 0) {
            response.end();
            return;
        }
        response.write(io.vertx.core.buffer.Buffer.buffer(new byte[64 * 1024]));
        if (response.writeQueueFull()) {
            response.drainHandler(drained -> {
                response.drainHandler(null);
                writeChunks(response, remaining - 1);
            });
        } else {
            vertx.runOnContext(next -> writeChunks(response, remaining - 1));
        }
    }

    /** One frame every {@link #GAP_MILLIS}, so arrival timing is the thing being measured. */
    private static void emit(HttpServerResponse response, int index) {
        if (index == EVENTS) {
            response.end();
            return;
        }
        response.write("data: event-" + index + "\n\n");
        vertx.setTimer(GAP_MILLIS, timer -> emit(response, index + 1));
    }

    // ---------------------------------------------------------------- machinery

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpResponse<InputStream> streamOf(String url) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    /** The body's length, without holding it. */
    private static long drain(HttpResponse<InputStream> response) throws Exception {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream body = response.body()) {
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
            }
        }
        return total;
    }

    /** Milliseconds from request start to each frame's arrival. */
    private static List<Long> readStream(String url) throws Exception {
        long start = System.nanoTime();
        List<Long> arrivals = new ArrayList<>();
        try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        arrivals.add((System.nanoTime() - start) / 1_000_000);
                    }
                }
            }
        }
        return arrivals;
    }
}
