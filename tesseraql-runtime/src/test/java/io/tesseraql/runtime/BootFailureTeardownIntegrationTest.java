package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * A failed boot releases what it took (docs/audit-hardening.md Decision 5, slice 5).
 *
 * <p>The boot-failure catch closed three TesseraQL objects and rethrew without stopping the Camel
 * context. Everything registered through {@code addService} — the platform HTTP server, the notify
 * bridge, the {@code LISTEN} connection — is started and stopped by the context, so the process
 * could stay listening on an address it had no runtime behind, and a retry in the same JVM died on
 * address-already-in-use.
 *
 * <p><b>Where the leak actually starts, which is not where the design said.</b> The design placed
 * the boundary at the {@code addService} call that registers the HTTP server. Measured, it is
 * {@code context.start()}: {@code addService} before start only registers, so a boot that fails
 * between the two has nothing bound to leak. Injecting a throw immediately after
 * {@code context.start()} and running this fixture against the old catch reproduces it exactly —
 * {@code java.net.BindException: Address already in use} — and the fix clears it.
 *
 * <p>That measurement is also why this test is a guard rather than a demonstration, and saying so
 * is better than letting it read as proof. No configuration reachable today fails the boot after
 * {@code context.start()}: the steps past it are a router handler and the SSE registrations, and
 * neither has a config-driven failure. So the fixture provokes the latest deterministic failure
 * that <em>is</em> reachable — an unparseable dispatch delay, past the datasources, the migrations,
 * the HTTP server and every compiled route — and asserts the invariant that held there too. It
 * starts failing the day a new startup step lands after {@code context.start()} and throws.
 */
@Testcontainers
class BootFailureTeardownIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void aFailedBootLeavesNothingListening() throws Exception {
        Path appHome = prepareAppHome();
        int port = freePort();
        try {
            assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, port))
                    .as("the fixture must fail the boot late, or this proves nothing")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to start TesseraQL runtime");

            // Binding is the assertion: it is what the next start attempt does, and it is what
            // used to fail.
            try (ServerSocket rebound = new ServerSocket(port)) {
                assertThat(rebound.isBound())
                        .as("port %d is still held by the runtime that failed to boot", port)
                        .isTrue();
            }
        } finally {
            delete(appHome);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-boot-teardown-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: boot-teardown-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  outbox:
                    dispatch:
                      fixedDelay: every-so-often
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path ping = target.resolve("web/api/ping");
        Files.createDirectories(ping);
        Files.writeString(ping.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: ping.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
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
