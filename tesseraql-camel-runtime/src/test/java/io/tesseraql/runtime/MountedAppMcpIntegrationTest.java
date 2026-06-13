package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end test for mounted-app MCP (roadmap Phase 24 mounted-app tools): a mounted app (design
 * ch. 32) declares its own MCP tool, resource, and UI resource under {@code mcp/}, and the runtime
 * serves them over the same {@code /_tesseraql/mcp} endpoint as the main app's, under each route's
 * own security. The MCP Apps UI extension is negotiated even when only the mounted app serves UI.
 */
@Testcontainers
class MountedAppMcpIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path mountedHome;
    static String session;

    @BeforeAll
    static void startRuntime() throws Exception {
        seedDatabase();
        mountedHome = prepareMountedApp();
        appHome = prepareAppHome(mountedHome);
        runtime = TesseraqlRuntime.start(appHome, freePort());
        session = initialize();
    }

    @AfterAll
    static void stopRuntime() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        for (Path root : new Path[]{appHome, mountedHome}) {
            if (root != null) {
                deleteRecursively(root);
            }
        }
    }

    @Test
    void toolsListAdvertisesBothMainAndMountedTools() throws Exception {
        JsonNode tools = rpc(rpcBody("tools/list", null), session, null).path("result")
                .path("tools");
        List<String> names = new java.util.ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        // The main app's own tool and the mounted app's tool share the one endpoint.
        assertThat(names).contains("whoami", "count-users");
    }

    @Test
    void aMountedAppToolRunsItsSqlForAnAuthorizedCaller() throws Exception {
        JsonNode result = call("count-users", Map.of(), token(List.of("USER_READ")));
        assertThat(result.path("isError").asBoolean()).as(() -> "result: " + result).isFalse();
        JsonNode rows = result.path("structuredContent").path("rows");
        assertThat(rows.get(0).path("total").asInt()).isEqualTo(3);
    }

    @Test
    void aMountedAppToolEnforcesItsPolicy() throws Exception {
        // The mounted tool declares auth: bearer + policy: users.read; the bearer token rides the
        // MCP request into its route, so an unauthenticated call is an MCP tool error.
        JsonNode noToken = call("count-users", Map.of(), null);
        assertThat(noToken.path("isError").asBoolean()).isTrue();

        JsonNode wrongRole = call("count-users", Map.of(), token(List.of("SOMETHING_ELSE")));
        assertThat(wrongRole.path("isError").asBoolean()).isTrue();
    }

    @Test
    void resourcesListAdvertisesTheMountedResource() throws Exception {
        JsonNode resources = rpc(rpcBody("resources/list", null), session, null).path("result")
                .path("resources");
        JsonNode directory = stream(resources)
                .filter(r -> r.path("uri").asText().equals("tesseraql://mounted/users"))
                .findFirst().orElseThrow();
        assertThat(directory.path("name").asText()).isEqualTo("mounted-users");
        assertThat(directory.path("mimeType").asText()).isEqualTo("application/json");
    }

    @Test
    void aMountedAppResourceReadRunsForAnAuthorizedCaller() throws Exception {
        JsonNode entry = readResource("tesseraql://mounted/users", token(List.of("USER_READ")))
                .path("result").path("contents").get(0);
        assertThat(entry.path("uri").asText()).isEqualTo("tesseraql://mounted/users");
        JsonNode rows = MAPPER.readTree(entry.path("text").asText()).path("rows");
        List<String> names = new java.util.ArrayList<>();
        rows.forEach(row -> names.add(row.path("name").asText()));
        assertThat(names).contains("sato");
    }

    @Test
    void aMountedAppResourceReadIsDeniedForAnUnauthorizedCaller() throws Exception {
        JsonNode noToken = readResource("tesseraql://mounted/users", null);
        assertThat(noToken.path("result").isMissingNode()).isTrue();
        assertThat(noToken.path("error").path("code").asInt()).isEqualTo(-32603);
    }

    @Test
    void initializeNegotiatesTheUiExtensionFromAMountedApp() throws Exception {
        // The main app declares no UI resource; the mounted app does, so the extension is still
        // advertised - the negotiation spans every hosted app.
        JsonNode ui = rpc(initializeBody(), null, null).path("result").path("capabilities")
                .path("extensions").path("io.modelcontextprotocol/ui");
        assertThat(ui.path("mimeTypes").get(0).asText()).isEqualTo("text/html;profile=mcp-app");
    }

    @Test
    void aMountedAppUiResourceIsListedAndRendered() throws Exception {
        JsonNode resources = rpc(rpcBody("resources/list", null), session, null).path("result")
                .path("resources");
        JsonNode board = stream(resources)
                .filter(r -> r.path("uri").asText().equals("ui://mounted/board"))
                .findFirst().orElseThrow();
        assertThat(board.path("mimeType").asText()).isEqualTo("text/html;profile=mcp-app");

        JsonNode entry = readResource("ui://mounted/board", token(List.of("USER_READ")))
                .path("result").path("contents").get(0);
        String html = entry.path("text").asText();
        assertThat(html).contains("hc-list").contains("sato");
    }

    // ----- MCP helpers ------------------------------------------------------

    private JsonNode call(String tool, Map<String, Object> arguments, String bearer)
            throws Exception {
        String params = MAPPER.writeValueAsString(Map.of("name", tool, "arguments", arguments));
        return rpc(rpcBody("tools/call", params), session, bearer).path("result");
    }

    private JsonNode readResource(String uri, String bearer) throws Exception {
        String params = MAPPER.writeValueAsString(Map.of("uri", uri));
        return rpc(rpcBody("resources/read", params), session, bearer);
    }

    private static String initialize() throws Exception {
        return post(initializeBody(), null, null).headers().firstValue("Mcp-Session-Id")
                .orElseThrow();
    }

    private static JsonNode rpc(String body, String session, String bearer) throws Exception {
        return MAPPER.readTree(post(body, session, bearer).body());
    }

    private static HttpResponse<String> post(String body, String session, String bearer)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    }

    private static String rpcBody(String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> nodes = new java.util.ArrayList<>();
        array.forEach(nodes::add);
        return nodes.stream();
    }

    // ----- fixtures ---------------------------------------------------------

    /** A mounted app whose entire surface is MCP: one tool, one resource, one UI resource. */
    private static Path prepareMountedApp() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-mounted-mcp");
        Path mcp = Files.createDirectories(home.resolve("mcp"));

        Files.writeString(mcp.resolve("count-users.yml"), """
                version: tesseraql/v1
                id: count-users
                kind: tool
                recipe: query-json
                description: Count all users. Use to size the directory.

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: count-users.sql
                  mode: query
                """);
        Files.writeString(mcp.resolve("count-users.sql"),
                "select count(*) as total from users\n;\n");

        Files.writeString(mcp.resolve("mounted-users.yml"), """
                version: tesseraql/v1
                id: mounted-users
                kind: resource
                recipe: query-json
                uri: tesseraql://mounted/users
                mimeType: application/json
                description: All users (id, name). Attach for directory context.

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: mounted-users.sql
                  mode: query
                """);
        Files.writeString(mcp.resolve("mounted-users.sql"),
                "select u.id, u.name from users u order by u.id\n;\n");

        Files.writeString(mcp.resolve("users-board.yml"), """
                version: tesseraql/v1
                id: mounted-board
                kind: ui
                recipe: query-html
                uri: ui://mounted/board
                description: A board of users, rendered as a Hypermedia Components fragment.

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: mounted-users.sql
                  mode: query

                response:
                  html:
                    template: users-board.html
                    model:
                      users: sql.rows

                ui:
                  prefersBorder: true
                """);
        Files.writeString(mcp.resolve("users-board.html"), """
                <section class="hc-card" xmlns:th="http://www.thymeleaf.org">
                  <ul class="hc-list">
                    <li class="hc-list__item" th:each="u : ${users}" th:text="${u.name}">name</li>
                  </ul>
                </section>
                """);
        return home;
    }

    /** The main app (the example) plus its own MCP tool and the mounted app under config. */
    private static Path prepareAppHome(Path mounted) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mounted-mcp-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  apps:
                    mounted:
                      path: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), mounted));

        // The main app declares one MCP tool of its own, so the endpoint serves both apps' tools.
        Path mcp = Files.createDirectories(target.resolve("mcp"));
        Files.writeString(mcp.resolve("whoami.yml"), """
                version: tesseraql/v1
                id: whoami
                kind: tool
                recipe: query-json
                description: Report which app answered.
                sql:
                  file: whoami.sql
                  mode: query
                """);
        Files.writeString(mcp.resolve("whoami.sql"), "select 'main' as app\n;\n");
        return target;
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u001", "preferred_username", "sato", "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table users (
                      id serial primary key,
                      name varchar(200) not null,
                      status varchar(32) not null,
                      created_at timestamp not null default now()
                    )""");
            statement.execute("""
                    insert into users (name, status) values
                      ('sato', 'ACTIVE'),
                      ('suzuki', 'ACTIVE'),
                      ('tanaka', 'INACTIVE')""");
        }
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
