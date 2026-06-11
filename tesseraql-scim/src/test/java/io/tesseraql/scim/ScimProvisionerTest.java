package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests outbound provisioning against an in-process stub SCIM provider that assigns remote ids and
 * records the requests it receives.
 */
class ScimProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger ids = new AtomicInteger();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private ScimProvisioner provisioner;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scim/v2/Users", this::handle);
        server.start();
        ScimTarget target = new ScimTarget(
                "http://localhost:" + server.getAddress().getPort() + "/scim/v2", "secret-token");
        provisioner = new ScimProvisioner(new ScimOutboundClient(target),
                new ScimResourceMapping.InMemory());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void provisionCreatesThenReplacesUsingMapping() throws Exception {
        ScimUser local = new ScimUser(null, null, "ext-1", "asmith",
                new ScimUser.Name("Anne", "Smith", null), List.of(), Boolean.TRUE);

        ScimUser created = provisioner.provision("local-1", local);
        assertThat(created.id()).startsWith("remote-");
        assertThat(authHeaders).allMatch("Bearer secret-token"::equals);

        // Second provision of the same local id replaces the existing remote resource (same id).
        ScimUser replaced = provisioner.provision("local-1",
                new ScimUser(null, null, "ext-1", "asmith",
                        new ScimUser.Name("Annette", "Smith", null), List.of(), Boolean.TRUE));
        assertThat(replaced.id()).isEqualTo(created.id());
        assertThat(replaced.name().givenName()).isEqualTo("Annette");
    }

    @Test
    void deprovisionDeletesRemoteAndClearsMapping() {
        ScimUser created = provisioner.provision("local-2",
                new ScimUser(null, null, null, "bjones", null, List.of(), Boolean.TRUE));

        provisioner.deprovision("local-2");
        assertThat(deleted).containsExactly(created.id());

        // Mapping is gone, so a subsequent provision creates a fresh remote resource.
        ScimUser recreated = provisioner.provision("local-2",
                new ScimUser(null, null, null, "bjones", null, List.of(), Boolean.TRUE));
        assertThat(recreated.id()).isNotEqualTo(created.id());
    }

    @Test
    void providerErrorBecomesScimException() {
        server.removeContext("/scim/v2/Users");
        server.createContext("/scim/v2/Users", exchange -> respond(exchange, 500, "{}"));

        assertThatThrownBy(() -> provisioner.provision("local-3",
                new ScimUser(null, null, null, "err", null, List.of(), Boolean.TRUE)))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> assertThat(((ScimException) ex).status()).isEqualTo(500));
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            if ("POST".equals(method)) {
                JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                String id = "remote-" + ids.incrementAndGet();
                respond(exchange, 201, withId(body, id));
            } else if ("PUT".equals(method)) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, withId(body, id));
            } else if ("DELETE".equals(method)) {
                deleted.add(path.substring(path.lastIndexOf('/') + 1));
                respond(exchange, 204, "");
            } else {
                respond(exchange, 405, "{}");
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
