package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.InstalledApp;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * docs/stack-architecture.md called the first of these out as the one thing that genuinely needed
 * measuring, because buffering there produces "working, but late" — the hardest failure to
 * diagnose.
 */
class StackRelayTest {

    private static final int EVENTS = 5;
    private static final long GAP_MILLIS = 300;
    private static final String APP = "stub";

    /**
     * The catalogue entry the stub is addressed by.
     *
     * <p>An application with no entry has no base path and therefore no address at all, since
     * routing became prefix-driven (docs/stack-architecture.md Decision 12). These tests used to
     * pass an empty map and be routed anyway, because the prefix was a constant the relay parsed;
     * now the entry is what says where the application answers.
     */
    private static final Map<String, InstalledApp> CATALOGUE = Map.of(APP,
            new InstalledApp(APP, "1.0.0", APP, java.util.List.of()));

    private static Vertx vertx;
    private static HttpClient client;
    private static HttpServer origin;
    private static HttpServer front;
    private static String base;
    private static int originPort;
    /** The same relay with cleartext HTTP/2 on, at both ends together. */
    private static HttpClient h2Client;
    private static HttpServer h2Front;
    private static String h2Base;
    /** What the origin actually received, so ingress stripping is observable on the wire. */
    private static volatile Map<String, String> lastRequestHeaders = Map.of();
    /** The protocol the origin saw on the second hop, so a case can prove both hops moved. */
    private static volatile String lastOriginVersion = "";

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();

        origin = vertx.createHttpServer(new HttpServerOptions().setPort(0));
        origin.requestHandler(StackRelayTest::serveStub);
        originPort = await(origin.listen()).actualPort();

        StackRelay relay = new StackRelay(client, CATALOGUE, appId -> originPort);
        front = vertx.createHttpServer(StackRelay.frontOptions(0, false));
        front.requestHandler(relay::handle);
        base = "http://localhost:" + await(front.listen()).actualPort() + "/" + APP;

        // The h2c pair. Enabling it at one end only is what breaks: a body arriving over HTTP/2
        // and piped into an HTTP/1.1 request has neither a declared length nor chunked framing,
        // and Vert.x refuses the write on the event loop. One setting moves both.
        h2Client = vertx.createHttpClient(StackRelay.outboundOptions(true));
        StackRelay h2Relay = new StackRelay(h2Client, CATALOGUE, appId -> originPort);
        h2Front = vertx.createHttpServer(StackRelay.frontOptions(0, true));
        h2Front.requestHandler(h2Relay::handle);
        h2Base = "http://localhost:" + await(h2Front.listen()).actualPort() + "/" + APP;
    }

    @AfterAll
    static void stop() throws Exception {
        if (h2Front != null) {
            await(h2Front.close());
        }
        if (h2Client != null) {
            await(h2Client.close());
        }
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

    /**
     * With an edge named, the forwarded header survives from it and not from anywhere else.
     *
     * <p>Loopback is the peer in a test, so naming {@code 127.0.0.1/32} names this client as the
     * edge and naming {@code 10.0.0.0/8} names something it is not. The comparison is against the
     * connection's peer rather than a header, which is the point — a caller can write
     * {@code X-Forwarded-For} and cannot write the socket it connected from.
     */
    @Test
    void theForwardedHeaderSurvivesFromTheNamedEdgeAndNotFromElsewhere() throws Exception {
        assertThat(headerSeenBehind(TrustedProxies.parse("127.0.0.1/32,::1")))
                .as("this client is the named edge, so its assertion is the edge's")
                .isEqualTo("from-the-edge");

        assertThat(headerSeenBehind(TrustedProxies.parse("10.0.0.0/8")))
                .as("this client is not the named edge, so its assertion is a caller's")
                .isNull();

        assertThat(headerSeenBehind(TrustedProxies.NONE))
                .as("no edge named: nothing is stripped, and the contract stays at the edge")
                .isEqualTo("from-the-edge");
    }

    /** What the origin received for {@code X-Client-Cert} through a relay trusting {@code edges}. */
    private static String headerSeenBehind(TrustedProxies edges) throws Exception {
        StackRelay relay = new StackRelay(client, CATALOGUE,
                Map.of(APP, Set.of("x-client-cert")), edges, appId -> originPort);
        HttpServer scoped = vertx.createHttpServer(StackRelay.frontOptions(0, false));
        scoped.requestHandler(relay::handle);
        int port = await(scoped.listen()).actualPort();
        try {
            send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/" + APP + "/echo"))
                    .header("X-Client-Cert", "from-the-edge")
                    .header("X-Tenant-Id", "tenant-a"));
            assertThat(lastRequestHeaders)
                    .as("the tenant header is never stripped: the app's own tenancy resolution"
                            + " is the authoritative one (docs/app-isolation-model.md decision 3)")
                    .containsEntry("x-tenant-id", "tenant-a");
            return lastRequestHeaders.get("x-client-cert");
        } finally {
            await(scoped.close());
        }
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

    /**
     * With HTTP/2 served and forwarded, the relay stays transparent.
     *
     * <p>The setting moves both hops on purpose, and this is why: an earlier build accepted h2c at
     * the front while the hop to the app stayed HTTP/1.1, and a request body arriving over HTTP/2
     * was piped into an outbound request with neither a declared length nor chunked framing —
     * Vert.x refused the write on the event loop, so an upload failed with nothing in the response
     * to say why. The body cases are the ones that catch it.
     */
    @Test
    void http2CarriesBodiesAndStreamsInBothDirections() throws Exception {
        int upload = 8 * 1024 * 1024;
        HttpResponse<String> sent = sendOver(Version.HTTP_2,
                HttpRequest.newBuilder(URI.create(h2Base + "/upload"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[upload])));

        assertThat(sent.version()).as("the client really did negotiate h2c")
                .isEqualTo(Version.HTTP_2);
        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(sent.body()).as("every byte crossed both hops")
                .isEqualTo(String.valueOf(upload));
        assertThat(lastOriginVersion).as("the second hop moved with the first").isEqualTo("HTTP_2");

        HttpResponse<String> chunked = sendOver(Version.HTTP_2,
                HttpRequest.newBuilder(URI.create(h2Base + "/chunked")));
        assertThat(chunked.body()).isEqualTo("one-two-three");

        List<Long> arrivals = readStream(h2Base + "/sse");
        assertThat(arrivals).hasSize(EVENTS);
        assertThat(arrivals.getLast() - arrivals.getFirst())
                .as("frames still arrive as they are written")
                .isGreaterThan(GAP_MILLIS * (EVENTS - 1) / 2);
    }

    /**
     * A body arriving over HTTP/1.1 at an h2c front still reaches the app.
     *
     * <p>Worth its own case because the first diagnosis of the h2c defect blamed the mismatched
     * pair, and the pair is not the problem: this one relays HTTP/1.1 in and HTTP/2 out and is
     * clean. What broke was a request the proxy treated as having a body of unknown length.
     */
    @Test
    void anHttp11BodyReachesTheAppThroughAnHttp2Front() throws Exception {
        int upload = 1024 * 1024;

        HttpResponse<String> sent = sendOver(Version.HTTP_1_1,
                HttpRequest.newBuilder(URI.create(h2Base + "/upload"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[upload])));

        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(sent.body()).isEqualTo(String.valueOf(upload));
        assertThat(lastOriginVersion).as("and the hop to the app still used HTTP/2")
                .isEqualTo("HTTP_2");
    }

    /**
     * An application that does not speak h2c stays reachable through an h2c front.
     *
     * <p>This is the claim the outbound options make and it has to be checked, because the
     * obvious alternative breaks it: asking for HTTP/2 with prior knowledge rather than through an
     * upgrade makes the client open with an HTTP/2 preface, an HTTP/1.1-only origin answers with
     * something else, and the connection is evicted — measured as a 502. The upgrade negotiates,
     * so an origin that declines it is served over HTTP/1.1 as before.
     */
    @Test
    void anAppThatDoesNotSpeakHttp2StaysReachable() throws Exception {
        HttpServer plainOrigin = vertx.createHttpServer(
                new HttpServerOptions().setPort(0).setHttp2ClearTextEnabled(false));
        plainOrigin.requestHandler(StackRelayTest::serveStub);
        int plainPort = await(plainOrigin.listen()).actualPort();
        StackRelay relay = new StackRelay(h2Client,
                CATALOGUE, appId -> plainPort);
        HttpServer h2FrontToPlain = vertx.createHttpServer(StackRelay.frontOptions(0, true));
        h2FrontToPlain.requestHandler(relay::handle);
        String plainBase = "http://localhost:" + await(h2FrontToPlain.listen()).actualPort()
                + "/" + APP;
        try {
            HttpResponse<String> response = sendOver(Version.HTTP_2,
                    HttpRequest.newBuilder(URI.create(plainBase + "/chunked")));

            assertThat(response.statusCode()).as("reachable, not a 502").isEqualTo(200);
            assertThat(response.body()).isEqualTo("one-two-three");
            assertThat(lastOriginVersion).as("served over HTTP/1.1, as that origin can")
                    .isEqualTo("HTTP_1_1");
        } finally {
            await(h2FrontToPlain.close());
            await(plainOrigin.close());
        }
    }

    /** An HTTP/1.1 client still reaches an h2c-enabled front; the upgrade is offered, not required. */
    @Test
    void anHttp11ClientStillReachesAnHttp2Front() throws Exception {
        HttpResponse<String> response = sendOver(Version.HTTP_1_1,
                HttpRequest.newBuilder(URI.create(h2Base + "/chunked")));

        assertThat(response.version()).isEqualTo(Version.HTTP_1_1);
        assertThat(response.body()).isEqualTo("one-two-three");
    }

    /**
     * The gateway answers the origin's own health itself — the load-balancer case
     * docs/stack-architecture.md decision 25 names, inside the framework's {@code /_tesseraql/}
     * fence that no application name can reach. The deployment images' healthchecks call this.
     */
    @Test
    void theOriginsHealthIsTheGatewaysOwn() throws Exception {
        String root = base.substring(0, base.lastIndexOf("/" + APP));

        HttpResponse<String> live = send(HttpRequest.newBuilder(
                URI.create(root + "/_tesseraql/health/live")));
        HttpResponse<String> ready = send(HttpRequest.newBuilder(
                URI.create(root + "/_tesseraql/health/ready")));

        assertThat(live.statusCode()).isEqualTo(200);
        assertThat(live.body()).contains("UP");
        assertThat(ready.statusCode()).isEqualTo(200);
    }

    /** No app answers at this address, and the relay says so rather than proxying. */
    @Test
    void anAddressNoAppAnswersIsRefusedHere() throws Exception {
        String root = base.substring(0, base.lastIndexOf("/" + APP));
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
        lastOriginVersion = String.valueOf(request.version());

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
        return sendOver(Version.HTTP_1_1, request);
    }

    /** Sends pinned to one protocol, so a case says which wire it exercised. */
    private static HttpResponse<String> sendOver(Version version, HttpRequest.Builder request)
            throws Exception {
        try (java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .version(version).build()) {
            return http.send(request.version(version).build(),
                    HttpResponse.BodyHandlers.ofString());
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
