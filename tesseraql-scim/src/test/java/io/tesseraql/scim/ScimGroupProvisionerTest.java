package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests outbound group provisioning against an in-process stub SCIM provider that assigns remote ids
 * and records the group requests (including the bidirectional member PATCH) it receives.
 */
class ScimGroupProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger ids = new AtomicInteger();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final List<JsonNode> patches = new CopyOnWriteArrayList<>();
    private ScimGroupProvisioner provisioner;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scim/v2/Groups", this::handle);
        server.start();
        ScimTarget target = new ScimTarget(
                "http://localhost:" + server.getAddress().getPort() + "/scim/v2", "secret-token");
        provisioner = new ScimGroupProvisioner(
                new ScimOutboundClient(target), new ScimResourceMapping.InMemory());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void provisionCreatesThenReplacesGroupUsingMapping() {
        ScimGroup local = new ScimGroup(null, null, "ext-1", "engineers",
                List.of(new ScimGroup.Member("100", null, null)));

        ScimGroup created = provisioner.provision("local-1", local);
        assertThat(created.id()).startsWith("remote-");

        // Second provision of the same local id replaces the existing remote group (same id).
        ScimGroup replaced = provisioner.provision("local-1",
                new ScimGroup(null, null, "ext-1", "engineering",
                        List.of(new ScimGroup.Member("100", null, null))));
        assertThat(replaced.id()).isEqualTo(created.id());
        assertThat(replaced.displayName()).isEqualTo("engineering");
    }

    @Test
    void syncMembersSendsBidirectionalPatch() {
        provisioner.provision("local-2",
                new ScimGroup(null, null, null, "ops", List.of(new ScimGroup.Member("100", null, null))));

        provisioner.syncMembers("local-2", List.of("200"), List.of("100"));

        assertThat(patches).hasSize(1);
        JsonNode operations = patches.get(0).get("Operations");
        assertThat(operations).hasSize(2);
        assertThat(operations.get(0).get("op").asText()).isEqualTo("add");
        assertThat(operations.get(0).get("value").get(0).get("value").asText()).isEqualTo("200");
        assertThat(operations.get(1).get("op").asText()).isEqualTo("remove");
        assertThat(operations.get(1).get("path").asText()).isEqualTo("members[value eq \"100\"]");
    }

    @Test
    void deprovisionDeletesRemoteAndClearsMapping() {
        ScimGroup created = provisioner.provision("local-3",
                new ScimGroup(null, null, null, "temp", List.of()));

        provisioner.deprovision("local-3");
        assertThat(deleted).containsExactly(created.id());

        // Mapping is gone, so a subsequent provision creates a fresh remote group.
        ScimGroup recreated = provisioner.provision("local-3",
                new ScimGroup(null, null, null, "temp", List.of()));
        assertThat(recreated.id()).isNotEqualTo(created.id());
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            switch (method) {
                case "POST" -> {
                    JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                    respond(exchange, 201, withId(body, "remote-" + ids.incrementAndGet()));
                }
                case "PUT" -> {
                    JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                    respond(exchange, 200, withId(body, path.substring(path.lastIndexOf('/') + 1)));
                }
                case "PATCH" -> {
                    patches.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
                    respond(exchange, 204, "");
                }
                case "DELETE" -> {
                    deleted.add(path.substring(path.lastIndexOf('/') + 1));
                    respond(exchange, 204, "");
                }
                default -> respond(exchange, 405, "{}");
            }
        } catch (RuntimeException ex) {
            respond(exchange, 500, "{}");
        }
    }

    private static String withId(JsonNode body, String id) {
        return ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("id", id).toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (bytes.length > 0) {
                    out.write(bytes);
                }
            }
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
