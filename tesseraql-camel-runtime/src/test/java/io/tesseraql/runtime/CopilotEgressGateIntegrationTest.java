package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The copilot endpoint obeys the outbound egress allow-list (docs/copilot.md): every chat
 * turn ships app source to the configured endpoint, so a copilot whose endpoint host is not
 * in {@code tesseraql.http.outbound.allowedHosts} must fail the boot with
 * {@code TQL-SEC-4085} — the same deny-by-default rule an {@code http-call} step obeys,
 * and the same fail-fast posture as the invite config ({@code TQL-SEC-4120}).
 */
@Testcontainers
class CopilotEgressGateIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** No allow-list at all: deny by default, and the error shows the exact YAML to add. */
    @Test
    void aCopilotWithoutAnyAllowListFailsTheBootWithTheYamlToAdd() throws Exception {
        Path appHome = prepareAppHome("");
        try {
            assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, freePort()))
                    .isInstanceOf(IllegalStateException.class)
                    .cause()
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-SEC-4085")
                    .hasMessageContaining("Copilot endpoint host 'api.example.com'")
                    .hasMessageContaining("tesseraql.http.outbound.allowedHosts")
                    .hasMessageContaining("allowedHosts:\n        - api.example.com");
        } finally {
            delete(appHome);
        }
    }

    /** An allow-list that names other hosts still refuses — the error names the host. */
    @Test
    void aCopilotEndpointHostOutsideTheAllowListFailsTheBoot() throws Exception {
        Path appHome = prepareAppHome("""
                  http:
                    outbound:
                      allowedHosts:
                        - api.partner.example
                """);
        try {
            assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, freePort()))
                    .isInstanceOf(IllegalStateException.class)
                    .cause()
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-SEC-4085")
                    .hasMessageContaining("api.example.com");
        } finally {
            delete(appHome);
        }
    }

    /** With the endpoint host allow-listed, the boot proceeds and the panel is live. */
    @Test
    void aCopilotWithAnAllowListedEndpointHostBoots() throws Exception {
        Path appHome = prepareAppHome("""
                  http:
                    outbound:
                      allowedHosts:
                        - api.example.com
                """);
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(appHome, freePort())) {
            assertThat(runtime.camelContext().getStatus().isStarted()).isTrue();
        } finally {
            delete(appHome);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome(String outboundBlock) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-copilot-egress-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: copilot-egress-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  studio:
                    enabled: true
                %s  copilot:
                    enabled: true
                    endpoint: https://api.example.com/v1/chat/completions
                    model: gated-test-model
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), outboundBlock));
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

    private static void delete(Path appHome) throws IOException {
        try (Stream<Path> files = Files.walk(appHome)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
