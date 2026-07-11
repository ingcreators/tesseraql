package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Studio copilot panel end to end (roadmap Phase 44; streaming per docs/copilot.md):
 * the chat drives a scripted OpenAI-compatible mock endpoint, the model's save_draft tool
 * call lands a real DRAFT (not a served change) plus an audit entry, the transcript renders
 * in the panel, and the htmx path streams — send returns the placeholder, the SSE stream
 * delivers chunk frames and a done payload identical to the page's own markup.
 */
@Testcontainers
class CopilotIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    static HttpServer modelServer;
    static final ConcurrentLinkedQueue<String> REPLIES = new ConcurrentLinkedQueue<>();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String sessionCookie;
    static String csrf;

    @BeforeAll
    static void start() throws Exception {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            String message = REPLIES.poll();
            // A stream:true call answers SSE deltas — content split in two and tool-call
            // arguments split across frames, so the client-side reassembly is exercised.
            if (request.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String frame : deltaFrames(message)) {
                        out.write(("data: " + frame + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            byte[] body = ("{\"choices\":[{\"message\":" + message + "}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        modelServer.start();

        appHome = prepareAppHome(
                "http://127.0.0.1:" + modelServer.getAddress().getPort()
                        + "/v1/chat/completions");
        runtime = TesseraqlRuntime.start(appHome, freePort());
        establishSession();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (modelServer != null) {
            modelServer.stop(0);
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Splits one canned message into OpenAI-style stream deltas (choices[0].delta). */
    private static List<String> deltaFrames(String message) throws IOException {
        com.fasterxml.jackson.databind.JsonNode canned = MAPPER.readTree(message);
        List<String> frames = new java.util.ArrayList<>();
        String content = canned.path("content").asText(null);
        if (content != null && !content.isEmpty()) {
            int mid = content.length() / 2;
            frames.add(frame(delta -> delta.put("content", content.substring(0, mid))));
            frames.add(frame(delta -> delta.put("content", content.substring(mid))));
        }
        com.fasterxml.jackson.databind.JsonNode call = canned.path("tool_calls").path(0);
        if (!call.isMissingNode()) {
            String arguments = call.path("function").path("arguments").asText();
            int mid = arguments.length() / 2;
            frames.add(frame(delta -> {
                var part = delta.putArray("tool_calls").addObject();
                part.put("index", 0).put("id", call.path("id").asText());
                part.putObject("function")
                        .put("name", call.path("function").path("name").asText())
                        .put("arguments", arguments.substring(0, mid));
            }));
            frames.add(frame(delta -> {
                var part = delta.putArray("tool_calls").addObject();
                part.put("index", 0);
                part.putObject("function").put("arguments", arguments.substring(mid));
            }));
        }
        return frames;
    }

    private static String frame(
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> delta)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        delta.accept(root.putArray("choices").addObject().putObject("delta"));
        return MAPPER.writeValueAsString(root);
    }

    @Test
    void theChatDrivesAToolCallIntoADraftAndRendersTheTranscript() throws Exception {
        REPLIES.addAll(List.of(
                """
                        {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",\
                        "function":{"name":"save_draft","arguments":\
                        "{\\"path\\":\\"web/api/ping/ping.sql\\",\\"content\\":\\"select 'v2' as answer\\\\n\\"}"}}]}""",
                "{\"role\":\"assistant\",\"content\":\"Saved a draft; review and apply it in"
                        + " the editor.\"}"));

        HttpResponse<String> sent = postForm("/_tesseraql/studio/ui/copilot/send",
                "message=" + URLEncoder.encode("bump ping to v2", StandardCharsets.UTF_8));
        assertThat(sent.statusCode()).isEqualTo(303);

        HttpResponse<String> panel = get("/_tesseraql/studio/ui/copilot");
        assertThat(panel.statusCode()).isEqualTo(200);
        assertThat(panel.body()).contains("bump ping to v2")
                .contains("used: save_draft")
                .contains("review and apply");

        // The draft is real; the served route is untouched until a human applies.
        assertThat(Files.readString(
                appHome.resolve("work/studio/drafts/web/api/ping/ping.sql")))
                .contains("v2");
        assertThat(Files.readString(appHome.resolve("web/api/ping/ping.sql")))
                .contains("v1");
        // And the audit trail names the chatting user.
        assertThat(Files.readString(appHome.resolve("work/studio/audit/audit.jsonl")))
                .contains("\"copilot\"");
        assertThat(REPLIES).isEmpty();
    }

    /** The htmx send/stream loop (docs/copilot.md): placeholder, chunks, then done. */
    @Test
    void anHtmxSendReturnsThePlaceholderAndTheStreamDeliversChunksThenDone()
            throws Exception {
        REPLIES.add("{\"role\":\"assistant\",\"content\":\"Streaming hello from the"
                + " copilot\"}");

        HttpResponse<String> sent = postHtmx("/_tesseraql/studio/ui/copilot/send",
                "message=" + URLEncoder.encode("stream me", StandardCharsets.UTF_8));
        assertThat(sent.statusCode()).isEqualTo(200);
        // The chat-messages fragments: the user item, the streaming placeholder, and the
        // out-of-band input clear.
        assertThat(sent.body()).contains("data-role=\"user\"")
                .contains("stream me")
                .contains("data-state=\"streaming\"")
                .contains("hx-ext=\"sse\"")
                .contains("sse-swap=\"done,error\"")
                .contains("id=\"copilot-message\"")
                .contains("hx-swap-oob=\"true\"");
        java.util.regex.Matcher turn = java.util.regex.Pattern
                .compile("stream\\?turn=([0-9a-f-]+)").matcher(sent.body());
        assertThat(turn.find()).isTrue();

        // The SSE stream: chunk frames while the model streams, one done frame at the end.
        HttpResponse<String> stream = get(
                "/_tesseraql/studio/ui/copilot/stream?turn=" + turn.group(1));
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");
        assertThat(stream.body()).contains("event: chunk");
        String done = stream.body().lines()
                .dropWhile(line -> !"event: done".equals(line)).skip(1).findFirst()
                .orElse("");
        assertThat(done).startsWith("data: ")
                .contains("Streaming hello from the copilot")
                .contains("data-role=\"assistant\"");

        // Single markup source: a page reload renders the exact bytes done carried.
        assertThat(get("/_tesseraql/studio/ui/copilot").body())
                .contains(done.substring("data: ".length()));
        // The turn id is single-use: replaying the stream URL is refused.
        assertThat(get("/_tesseraql/studio/ui/copilot/stream?turn=" + turn.group(1))
                .statusCode()).isEqualTo(404);
        assertThat(REPLIES).isEmpty();
    }

    /** A streamed tool turn: marker chunks, reassembled split arguments, a real draft. */
    @Test
    void aStreamedToolTurnReassemblesArgumentsAndLandsTheDraft() throws Exception {
        REPLIES.addAll(List.of(
                """
                        {"role":"assistant","content":null,"tool_calls":[{"id":"c2","type":"function",\
                        "function":{"name":"save_draft","arguments":\
                        "{\\"path\\":\\"web/api/ping/ping.sql\\",\\"content\\":\\"select 'v3' as answer\\\\n\\"}"}}]}""",
                "{\"role\":\"assistant\",\"content\":\"Draft v3 saved; review and apply.\"}"));

        HttpResponse<String> sent = postHtmx("/_tesseraql/studio/ui/copilot/send",
                "message=" + URLEncoder.encode("bump ping to v3", StandardCharsets.UTF_8));
        java.util.regex.Matcher turn = java.util.regex.Pattern
                .compile("stream\\?turn=([0-9a-f-]+)").matcher(sent.body());
        assertThat(turn.find()).isTrue();

        HttpResponse<String> stream = get(
                "/_tesseraql/studio/ui/copilot/stream?turn=" + turn.group(1));
        // The tool marker chunk rode the stream, and done shows the tool affordance.
        assertThat(stream.body()).contains("save_draft")
                .contains("event: done")
                .contains("used: save_draft")
                .contains("Draft v3 saved");
        // The mock split the tool arguments across two delta frames; the reassembled call
        // still landed the full draft content.
        assertThat(Files.readString(
                appHome.resolve("work/studio/drafts/web/api/ping/ping.sql")))
                .contains("v3");
        assertThat(REPLIES).isEmpty();
    }

    /** The turn id is the capability: unknown or foreign ids answer 404, not a stream. */
    @Test
    void anUnknownTurnIdIsRefusedBeforeTheStreamOpens() throws Exception {
        HttpResponse<String> stream = get(
                "/_tesseraql/studio/ui/copilot/stream?turn=not-a-turn");
        assertThat(stream.statusCode()).isEqualTo(404);
    }

    /** The htmx path validates like the YAML route did: a blank message is a 400. */
    @Test
    void aBlankMessageIsRefused() throws Exception {
        assertThat(postHtmx("/_tesseraql/studio/ui/copilot/send", "message=")
                .statusCode()).isEqualTo(400);
        assertThat(postForm("/_tesseraql/studio/ui/copilot/send", "message=")
                .statusCode()).isEqualTo(400);
    }

    private static void establishSession() {
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext()
                .getRegistry().lookupByNameAndType(
                        io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String sid = sessions.create(new io.tesseraql.security.Principal(
                "copilot-user", "copilot-user", "Copilot User", null, List.of(),
                List.of("ADMIN"), List.of(), java.util.Map.of()));
        sessionCookie = sessions.cookieName() + "=" + sid;
        csrf = sessions.session(sid).csrfToken();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", sessionCookie)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A form post the way htmx sends it — the HX-Request header selects the fragment path. */
    private static HttpResponse<String> postHtmx(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", csrf)
                .header("HX-Request", "true")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome(String modelEndpoint) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-copilot-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: copilot-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  studio:
                    enabled: true
                    readOnly: false
                    editRoles: ADMIN
                  http:
                    outbound:
                      allowedHosts:
                        - 127.0.0.1
                  copilot:
                    enabled: true
                    endpoint: %s
                    model: scripted-test-model
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), modelEndpoint));
        Path ping = target.resolve("web/api/ping");
        Files.createDirectories(ping);
        Files.writeString(ping.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(ping.resolve("ping.sql"), "select 'v1' as answer\n");
        return target;
    }
}
