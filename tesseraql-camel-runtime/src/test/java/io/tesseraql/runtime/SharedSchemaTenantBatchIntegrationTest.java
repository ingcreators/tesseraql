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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for shared-schema per-tenant batch fan-out (design ch. 30.3). The tenant list
 * comes from a SQL tenant registry; a {@code perTenant} job runs once per registered tenant on the
 * shared datasource, scoping its rows by the bound {@code tenant.id}.
 */
@Testcontainers
class SharedSchemaTenantBatchIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void perTenantJobFansOutOverRegistryOnSharedDatasource() throws Exception {
        List<JobExecution> executions = runtime.runJobForAllTenants("items.seed", Map.of());

        assertThat(executions).hasSize(2);
        assertThat(executions).allMatch(e -> e.status() == JobStatus.COMPLETED);
        assertThat(rowCountFor("acme")).isEqualTo(1);
        assertThat(rowCountFor("globex")).isEqualTo(1);
    }

    private static int rowCountFor(String tenant) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from items where tenant_id = '" + tenant + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table tenants (tenant_id varchar(64) primary key)");
            statement.execute("insert into tenants (tenant_id) values ('acme'), ('globex')");
            statement.execute("create table items (id serial primary key, "
                    + "tenant_id varchar(64) not null, name varchar(200) not null)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-shared-tenant-batch-it");
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

                tenancy:
                  enabled: true
                  mode: shared-schema
                  required: true
                  resolver:
                    type: header
                    source: X-Tenant-Id
                  registry:
                    sql: select tenant_id from tenants order by tenant_id
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

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
                  params:
                    tenant_id: tenant.id
                """);
        Files.writeString(jobDir.resolve("seed.sql"),
                "insert into items (tenant_id, name) values (/* tenant_id */ 'x', 'seeded')\n");
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
