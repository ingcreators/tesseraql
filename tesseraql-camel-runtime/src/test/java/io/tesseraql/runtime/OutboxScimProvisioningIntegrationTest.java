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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for outbound SCIM provisioning driven by the outbox (design ch. 10.15, 39.2): a
 * USER_PROVISIONED outbox event, once dispatched, provisions the user to a downstream SCIM provider.
 */
@Testcontainers
class OutboxScimProvisioningIntegrationTest {

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
        provider.createContext("/scim/v2/Users", OutboxScimProvisioningIntegrationTest::handle);
        provider.createContext("/scim/v2/Groups", OutboxScimProvisioningIntegrationTest::handleGroup);
        provider.start();

        appHome = prepareAppHome(provider.getAddress().getPort());
        runtime = TesseraqlRuntime.start(appHome, freePort());
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
    void outboxEventProvisionsUserToProvider() throws Exception {
        String payload = "{\"userName\":\"asmith\",\"name\":{\"givenName\":\"Anne\",\"familyName\":\"Smith\"},"
                + "\"active\":true}";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            runtime.outboxStore().insert(connection,
                    OutboxEvent.toInsert("User", "local-1", "USER_PROVISIONED", payload, "user-admin"));
        }

        int delivered = runtime.dispatchOutboxOnce();
        assertThat(delivered).isGreaterThanOrEqualTo(1);

        assertThat(provisioned).anySatisfy(user ->
                assertThat(user.get("userName").asText()).isEqualTo("asmith"));
    }

    @Test
    void outboxEventProvisionsGroupToProvider() throws Exception {
        String payload = "{\"displayName\":\"engineers\",\"members\":[{\"value\":\"100\"}]}";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            runtime.outboxStore().insert(connection,
                    OutboxEvent.toInsert("Group", "local-g1", "GROUP_PROVISIONED", payload, "user-admin"));
        }

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
            byte[] response = ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                    .put("id", "remote-g1").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void handle(HttpExchange exchange) {
        try {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            provisioned.add(body);
            byte[] response = ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                    .put("id", "remote-1").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Path prepareAppHome(int providerPort) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-outbox-scim-it");
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
