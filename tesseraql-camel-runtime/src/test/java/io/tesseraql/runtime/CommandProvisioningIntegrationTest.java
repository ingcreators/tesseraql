package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.outbox.OutboxEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Integration test for automatic provisioning-event emission from a command route (design ch. 10.15,
 * 39.2): a write route declares {@code outbox.eventType: USER_PROVISIONED} with a SCIM-shaped payload
 * (nested via dotted keys), so the command commits the change and the provisioning event atomically,
 * and the dispatcher provisions the user to the downstream SCIM provider.
 */
@Testcontainers
class CommandProvisioningIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<JsonNode> provisioned = new CopyOnWriteArrayList<>();
    private static final List<JsonNode> provisionedGroups = new CopyOnWriteArrayList<>();

    static HttpServer provider;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        provider = HttpServer.create(new InetSocketAddress(0), 0);
        provider.createContext("/scim/v2/Users", CommandProvisioningIntegrationTest::handle);
        provider.createContext("/scim/v2/Groups", CommandProvisioningIntegrationTest::handleGroup);
        provider.start();
        appHome = prepareAppHome(provider.getAddress().getPort());
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (provider != null) {
            provider.stop(0);
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void commandRouteEmitsScimProvisioningEventAndDispatches() throws Exception {
        HttpResponse<String> response = post("/api/users/provision",
                "{\"userName\":\"kim\",\"externalId\":\"ext-9\",\"givenName\":\"Kim\","
                        + "\"familyName\":\"Lee\",\"active\":true}");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("affected").asInt()).isEqualTo(1);
        assertThat(body.path("eventId").asText()).isNotBlank();

        // The event is queued with a SCIM-shaped, nested payload assembled from dotted payload keys.
        OutboxEvent event = runtime.outboxStore().listPending(50).stream()
                .filter(e -> "USER_PROVISIONED".equals(e.eventType()))
                .findFirst().orElseThrow();
        JsonNode payload = MAPPER.readTree(event.payloadJson());
        assertThat(payload.get("userName").asText()).isEqualTo("kim");
        assertThat(payload.get("name").get("givenName").asText()).isEqualTo("Kim");
        assertThat(payload.get("active").asBoolean()).isTrue();

        int delivered = runtime.dispatchOutboxOnce();
        assertThat(delivered).isGreaterThanOrEqualTo(1);

        // The downstream SCIM provider received the structured user.
        assertThat(provisioned).anySatisfy(user -> {
            assertThat(user.get("userName").asText()).isEqualTo("kim");
            assertThat(user.get("name").get("givenName").asText()).isEqualTo("Kim");
        });
    }

    @Test
    void commandRouteEmitsScimGroupProvisioningEventWithMembers() throws Exception {
        HttpResponse<String> response = post("/api/groups/provision",
                "{\"displayName\":\"engineers\",\"externalId\":\"grp-1\","
                        + "\"memberIds\":[\"100\",\"200\"]}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("affected").asInt()).isEqualTo(1);

        // The members array is assembled from the request id list via the members[].value key.
        OutboxEvent event = runtime.outboxStore().listPending(50).stream()
                .filter(e -> "GROUP_PROVISIONED".equals(e.eventType()))
                .findFirst().orElseThrow();
        JsonNode members = MAPPER.readTree(event.payloadJson()).get("members");
        assertThat(members).hasSize(2);
        assertThat(members.get(0).get("value").asText()).isEqualTo("100");
        assertThat(members.get(1).get("value").asText()).isEqualTo("200");

        int delivered = runtime.dispatchOutboxOnce();
        assertThat(delivered).isGreaterThanOrEqualTo(1);

        assertThat(provisionedGroups).anySatisfy(group -> {
            assertThat(group.get("displayName").asText()).isEqualTo("engineers");
            assertThat(group.get("members").get(0).get("value").asText()).isEqualTo("100");
        });
    }

    private static void handleGroup(HttpExchange exchange) {
        try {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            provisionedGroups.add(body);
            byte[] out = ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                    .put("id", "remote-g1").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void handle(HttpExchange exchange) {
        try {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            provisioned.add(body);
            byte[] out = ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                    .put("id", "remote-1").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin", "roles",
                        List.of("USER_WRITE"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("insert into users (name, status) values ('kim','INACTIVE')");
            statement.execute("truncate table app_groups restart identity");
            statement.execute("insert into app_groups (display_name) values ('engineers')");
        }
    }

    private static Path prepareAppHome(int providerPort) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-cmd-provision-it");
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
                  scim:
                    outbound:
                      enabled: true
                      target:
                        url: http://localhost:%d/scim/v2
                        token: provider-token
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), providerPort));
        return target;
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
