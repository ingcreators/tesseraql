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
 * End-to-end test for application-declared MCP endpoints (roadmap Phase 24 follow-on): an app
 * declares MCP tools under {@code mcp/}, and the runtime serves them over Streamable HTTP at
 * {@code /_tesseraql/mcp}. A query tool reads through 2-way SQL; a command tool writes through the
 * transactional pipeline; both enforce their own route security (the bearer token rides the MCP
 * request), so an unauthenticated or under-privileged call comes back as an MCP tool error.
 */
@Testcontainers
class AppMcpToolIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String session;

    @BeforeAll
    static void startRuntime() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        session = initialize();
    }

    @AfterAll
    static void stopRuntime() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void initializeReportsTheApplicationAsTheServer() throws Exception {
        JsonNode result = rpc(initializeBody(), null, null).path("result");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("user-admin");
        assertThat(result.path("protocolVersion").asText()).isNotBlank();
    }

    @Test
    void toolsListAdvertisesTheDeclaredToolsWithSchemas() throws Exception {
        JsonNode tools = rpc(rpcBody("tools/list", null), session, null).path("result")
                .path("tools");
        List<String> names = new java.util.ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        assertThat(names).contains("find-users", "deactivate-user");
        JsonNode findUsers = stream(tools).filter(t -> t.path("name").asText().equals("find-users"))
                .findFirst().orElseThrow();
        assertThat(findUsers.path("inputSchema").path("properties").path("q").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    void aQueryToolRunsItsSqlForAnAuthorizedCaller() throws Exception {
        JsonNode result = call("find-users", Map.of("q", "sato"), token(List.of("USER_READ")));
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode rows = result.path("structuredContent").path("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("name").asText()).isEqualTo("sato");
    }

    @Test
    void aToolEnforcesItsPolicy() throws Exception {
        JsonNode noToken = call("find-users", Map.of("q", "sato"), null);
        assertThat(noToken.path("isError").asBoolean()).isTrue();

        JsonNode wrongRole = call("find-users", Map.of("q", "sato"),
                token(List.of("SOMETHING_ELSE")));
        assertThat(wrongRole.path("isError").asBoolean()).isTrue();
    }

    @Test
    void aCommandToolWritesThroughTheTransactionalPipeline() throws Exception {
        JsonNode deactivate = call("deactivate-user", Map.of("name", "suzuki"),
                token(List.of("USER_WRITE")));
        assertThat(deactivate.path("isError").asBoolean())
                .as(() -> "deactivate result: " + deactivate).isFalse();

        // The write is visible through the query tool: suzuki is now INACTIVE.
        JsonNode rows = call("find-users", Map.of("q", "suzuki"), token(List.of("USER_READ")))
                .path("structuredContent").path("rows");
        assertThat(rows.get(0).path("status").asText()).isEqualTo("INACTIVE");
    }

    // ----- MCP helpers ------------------------------------------------------

    private JsonNode call(String tool, Map<String, Object> arguments, String bearer)
            throws Exception {
        String params = MAPPER.writeValueAsString(Map.of("name", tool, "arguments", arguments));
        return rpc(rpcBody("tools/call", params), session, bearer).path("result");
    }

    private static String initialize() throws Exception {
        HttpResponse<String> response = post(initializeBody(), null, null);
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
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

    // ----- fixture (mirrors QueryJsonIntegrationTest) -----------------------

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mcp-app");
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        // The app declares two MCP tools over the same users table its routes use.
        Path mcp = Files.createDirectories(target.resolve("mcp"));
        Files.writeString(mcp.resolve("find-users.yml"), """
                version: tesseraql/v1
                id: find-users
                kind: tool
                recipe: query-json
                description: Search users by name; returns id, name, and status.

                input:
                  q:
                    type: string
                    required: false
                    maxLength: 200

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: find.sql
                  mode: query
                  params:
                    q: query.q
                """);
        Files.writeString(mcp.resolve("find.sql"), """
                select u.id, u.name, u.status
                from users u
                where 1 = 1
                /*%if q != null && q != "" */
                  and u.name like /* q */ '%sato%'
                /*%end*/
                order by u.id
                """);
        Files.writeString(mcp.resolve("deactivate-user.yml"), """
                version: tesseraql/v1
                id: deactivate-user
                kind: tool
                recipe: command-json
                description: Deactivate a user by name (sets status to INACTIVE).

                input:
                  name:
                    type: string
                    required: true
                    maxLength: 200

                security:
                  auth: bearer
                  policy: users.write

                sql:
                  file: deactivate.sql
                  mode: update
                  params:
                    name: query.name
                """);
        Files.writeString(mcp.resolve("deactivate.sql"), """
                update users set status = 'INACTIVE'
                where name = /* name */ 'sato'
                """);
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
