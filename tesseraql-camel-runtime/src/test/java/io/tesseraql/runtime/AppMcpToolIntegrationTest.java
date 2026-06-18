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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end test for application-declared MCP endpoints (roadmap Phase 24): an app declares MCP
 * tools and resources under {@code mcp/}, and the runtime serves them over Streamable HTTP at
 * {@code /_tesseraql/mcp}. A query tool reads through 2-way SQL; a command tool writes through the
 * transactional pipeline; a read-only resource exposes table data addressed by its uri. All enforce
 * their own route security (the bearer token rides the MCP request), so an unauthenticated or
 * under-privileged call comes back as an MCP tool error or a {@code resources/read} JSON-RPC error.
 */
@Testcontainers
class AppMcpToolIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String session;

    @BeforeAll
    static void startRuntime() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
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

    @Test
    void initializeAdvertisesTheResourcesCapability() throws Exception {
        JsonNode capabilities = rpc(initializeBody(), null, null).path("result")
                .path("capabilities");
        assertThat(capabilities.path("resources").path("subscribe").asBoolean()).isFalse();
    }

    @Test
    void resourcesListAdvertisesTheDeclaredResource() throws Exception {
        JsonNode resources = rpc(rpcBody("resources/list", null), session, null).path("result")
                .path("resources");
        JsonNode active = stream(resources)
                .filter(r -> r.path("uri").asText().equals("tesseraql://users/active"))
                .findFirst().orElseThrow();
        assertThat(active.path("name").asText()).isEqualTo("active-users");
        assertThat(active.path("mimeType").asText()).isEqualTo("application/json");
        assertThat(active.path("description").asText()).isNotBlank();
    }

    @Test
    void aResourceReadRunsItsSqlForAnAuthorizedCaller() throws Exception {
        JsonNode entry = readResource("tesseraql://users/active", token(List.of("USER_READ")))
                .path("result").path("contents").get(0);
        assertThat(entry.path("uri").asText()).isEqualTo("tesseraql://users/active");
        assertThat(entry.path("mimeType").asText()).isEqualTo("application/json");
        JsonNode rows = MAPPER.readTree(entry.path("text").asText()).path("rows");
        List<String> names = new java.util.ArrayList<>();
        rows.forEach(row -> names.add(row.path("name").asText()));
        assertThat(names).contains("sato");
    }

    @Test
    void aResourceReadIsDeniedForAnUnauthorizedCaller() throws Exception {
        JsonNode noToken = readResource("tesseraql://users/active", null);
        assertThat(noToken.path("result").isMissingNode()).isTrue();
        assertThat(noToken.path("error").path("code").asInt()).isEqualTo(-32603);
    }

    @Test
    void aResourceReadOfAnUnknownUriIsTheResourceNotFoundError() throws Exception {
        JsonNode unknown = readResource("tesseraql://users/nope", token(List.of("USER_READ")));
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32002);
    }

    @Test
    void initializeAdvertisesTheMcpAppsUiExtension() throws Exception {
        JsonNode ui = rpc(initializeBody(), null, null).path("result").path("capabilities")
                .path("extensions").path("io.modelcontextprotocol/ui");
        assertThat(ui.path("mimeTypes").get(0).asText()).isEqualTo("text/html;profile=mcp-app");
    }

    @Test
    void toolsListLinksALinkingToolToItsUiResource() throws Exception {
        JsonNode tools = rpc(rpcBody("tools/list", null), session, null).path("result")
                .path("tools");
        JsonNode findUsers = stream(tools).filter(t -> t.path("name").asText().equals("find-users"))
                .findFirst().orElseThrow();
        assertThat(findUsers.path("_meta").path("ui").path("resourceUri").asText())
                .isEqualTo("ui://users/board");
    }

    @Test
    void resourcesListAdvertisesTheUiResourceWithTheMcpAppProfile() throws Exception {
        JsonNode resources = rpc(rpcBody("resources/list", null), session, null).path("result")
                .path("resources");
        JsonNode board = stream(resources)
                .filter(r -> r.path("uri").asText().equals("ui://users/board"))
                .findFirst().orElseThrow();
        assertThat(board.path("mimeType").asText()).isEqualTo("text/html;profile=mcp-app");
        assertThat(board.path("_meta").path("ui").path("prefersBorder").asBoolean()).isTrue();
    }

    @Test
    void aUiResourceReadRendersTheHcFragmentForAnAuthorizedCaller() throws Exception {
        JsonNode entry = readResource("ui://users/board", token(List.of("USER_READ")))
                .path("result").path("contents").get(0);
        assertThat(entry.path("uri").asText()).isEqualTo("ui://users/board");
        assertThat(entry.path("mimeType").asText()).isEqualTo("text/html;profile=mcp-app");
        // The fragment is server-rendered hc-* markup carrying the active users.
        String html = entry.path("text").asText();
        assertThat(html).contains("hc-list").contains("sato");
    }

    @Test
    void aUiResourceReadIsDeniedForAnUnauthorizedCaller() throws Exception {
        JsonNode noToken = readResource("ui://users/board", null);
        assertThat(noToken.path("result").isMissingNode()).isTrue();
        assertThat(noToken.path("error").path("code").asInt()).isEqualTo(-32603);
    }

    @Test
    void initializeAdvertisesThePromptsCapability() throws Exception {
        JsonNode capabilities = rpc(initializeBody(), null, null).path("result")
                .path("capabilities");
        assertThat(capabilities.path("prompts").path("listChanged").asBoolean()).isFalse();
    }

    @Test
    void promptsListAdvertisesTheDeclaredPromptWithItsArguments() throws Exception {
        JsonNode prompts = rpc(rpcBody("prompts/list", null), session, null).path("result")
                .path("prompts");
        JsonNode draft = stream(prompts)
                .filter(p -> p.path("name").asText().equals("draft-welcome"))
                .findFirst().orElseThrow();
        assertThat(draft.path("description").asText()).isNotBlank();
        JsonNode name = stream(draft.path("arguments"))
                .filter(a -> a.path("name").asText().equals("name")).findFirst().orElseThrow();
        assertThat(name.path("required").asBoolean()).isTrue();
        assertThat(name.path("description").asText()).isEqualTo("The new user's name.");
    }

    @Test
    void promptsGetRendersTheTemplateAgainstTheArguments() throws Exception {
        String params = MAPPER.writeValueAsString(Map.of("name", "draft-welcome",
                "arguments", Map.of("name", "sato", "tone", "warm")));
        JsonNode result = rpc(rpcBody("prompts/get", params), session, null).path("result");
        JsonNode message = result.path("messages").get(0);
        assertThat(message.path("role").asText()).isEqualTo("user");
        assertThat(message.path("content").path("text").asText())
                .isEqualTo("Write a warm welcome message for sato.");
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
                ui: ui://users/board

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

        // The app also declares a read-only MCP resource over the same table: an agent attaches
        // it as context. It is addressed by its uri, takes no arguments, and enforces users.read.
        Files.writeString(mcp.resolve("active-users.yml"), """
                version: tesseraql/v1
                id: active-users
                kind: resource
                recipe: query-json
                uri: tesseraql://users/active
                mimeType: application/json
                description: Active users (id, name). Attach for user-directory context.

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: active-users.sql
                  mode: query
                """);
        Files.writeString(mcp.resolve("active-users.sql"), """
                select u.id, u.name
                from users u
                where u.status = 'ACTIVE'
                order by u.id
                """);

        // The app also declares an MCP Apps UI resource (the MCP Apps extension): a query-html
        // definition that server-renders an hc-* fragment of active users, addressed by a ui:// uri.
        // The find-users tool links to it via its ui: field.
        Files.writeString(mcp.resolve("users-board.yml"), """
                version: tesseraql/v1
                id: users-board
                kind: ui
                recipe: query-html
                uri: ui://users/board
                description: A board of active users, rendered as a Hypermedia Components fragment.

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: users-board.sql
                  mode: query

                response:
                  html:
                    template: users-board.html
                    model:
                      users: sql.rows

                ui:
                  prefersBorder: true
                  csp:
                    connectDomains: ["'self'"]
                """);
        Files.writeString(mcp.resolve("users-board.sql"), """
                select u.id, u.name
                from users u
                where u.status = 'ACTIVE'
                order by u.id
                """);
        Files.writeString(mcp.resolve("users-board.html"), """
                <section class="hc-card" xmlns:th="http://www.thymeleaf.org">
                  <ul class="hc-list">
                    <li class="hc-list__item" th:each="u : ${users}" th:text="${u.name}">name</li>
                  </ul>
                </section>
                """);

        // The app also declares an MCP prompt (kind: prompt): a parameterized, reusable message
        // template the connecting agent surfaces to its model. It runs no SQL - its colocated TEXT
        // template is rendered against the supplied arguments and returned by prompts/get.
        Files.writeString(mcp.resolve("draft-welcome.yml"), """
                version: tesseraql/v1
                id: draft-welcome
                kind: prompt
                description: Draft a welcome message for a new user.

                input:
                  name:
                    type: string
                    required: true
                    description: The new user's name.
                  tone:
                    type: string
                    required: false

                template: draft-welcome.txt.tpl
                """);
        Files.writeString(mcp.resolve("draft-welcome.txt.tpl"),
                "Write a [(${tone})] welcome message for [(${name})].");
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
            statement.execute("truncate table users restart identity");
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
