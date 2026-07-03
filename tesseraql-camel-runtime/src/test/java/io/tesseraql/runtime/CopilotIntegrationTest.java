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
 * The Studio copilot panel end to end (roadmap Phase 44): the chat drives a scripted
 * OpenAI-compatible mock endpoint, the model's save_draft tool call lands a real DRAFT (not
 * a served change) plus an audit entry, and the transcript renders in the panel.
 */
@Testcontainers
class CopilotIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
            String message = REPLIES.poll();
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
