package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for per-tenant batch execution (design ch. 30.3). A {@code perTenant} job runs
 * once for each configured tenant, each on the tenant's own datasource, so each tenant's schema is
 * seeded independently.
 */
@Testcontainers
class TenantBatchIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void perTenantJobRunsOncePerTenant() throws Exception {
        List<JobExecution> executions = runtime.runJobForAllTenants("items.seed", Map.of());

        assertThat(executions).hasSize(2);
        assertThat(executions).allMatch(e -> e.status() == JobStatus.COMPLETED);
        assertThat(rowCount("acme")).isEqualTo(1);
        assertThat(rowCount("globex")).isEqualTo(1);
    }

    private static int rowCount(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from " + schema + ".items where name = 'seeded'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String tenant : new String[]{"acme", "globex"}) {
                statement.execute("create schema " + tenant);
                statement.execute("create table " + tenant
                        + ".items (id serial primary key, name varchar(200) not null)");
            }
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-tenant-batch-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        String baseUrl = POSTGRES.getJdbcUrl();
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %1$s
                    username: %2$s
                    password: %3$s

                tenancy:
                  enabled: true
                  mode: database-per-tenant
                  required: true
                  resolver:
                    type: header
                    source: X-Tenant-Id
                  datasources:
                    acme:
                      jdbcUrl: %1$s&currentSchema=acme
                      username: %2$s
                      password: %3$s
                    globex:
                      jdbcUrl: %1$s&currentSchema=globex
                      username: %2$s
                      password: %3$s
                """.formatted(baseUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path jobDir = target.resolve("batch/items/seed");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: items.seed
                kind: job
                recipe: batch-tasklet
                perTenant: true
                sql:
                  file: seed.sql
                  mode: update
                """);
        Files.writeString(jobDir.resolve("seed.sql"),
                "insert into items (name) values ('seeded')\n");
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
