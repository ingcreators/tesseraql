package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.tenant.PoolRole;
import io.tesseraql.yaml.config.AppConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * main's role pools (docs/capacity-defaults.md decisions 5, 5a and 5b), against a real database:
 * a job run borrows from main's {@code jobPool}, a route-started transfer from its
 * {@code fileTransferPool}, and in a per-tenant mode a tenant's transfer from that tenant's.
 *
 * <p>Which pool served the work is read where an operator reads it: the scrape, which reports
 * main's role pools as {@code main.jobPool} and {@code main.fileTransferPool}, holding a
 * connection while the work sleeps in the database. Nothing else borrows from a role pool, so an
 * active connection there is the work under test.
 */
@Testcontainers
class RolePoolsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;

    @BeforeAll
    static void start() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create schema acme");
            statement.execute("create schema globex");
        }
        runtime = TesseraqlRuntime.start(appHome(), 0);
    }

    @AfterAll
    static void stop() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void aJobRunsOnMainsJobPool() throws Exception {
        CompletableFuture<io.tesseraql.operations.batch.JobExecution> run = CompletableFuture
                .supplyAsync(() -> runtime.runJob("slow", Map.of()));

        assertThat(awaitActive("main.jobPool")).as("the job's connection").isGreaterThan(0);
        assertThat(run.get().status().name()).isEqualTo("COMPLETED");
        assertThat(active("main.jobPool")).isZero();
    }

    /**
     * A route transfer runs on main's file-transfer pool, and — because that pool is main's own
     * database — still commits its rows and its verdict together: it completes.
     */
    @Test
    void aRouteTransferRunsOnMainsFileTransferPool() throws Exception {
        String transferId = startExport(null);

        assertThat(awaitActive("main.fileTransferPool")).as("the transfer's connection")
                .isGreaterThan(0);
        assertThat(awaitTerminal(null, transferId)).isEqualTo("COMPLETED");
        assertThat(file(null, transferId)).contains("public");
    }

    /**
     * A tenant's transfer runs on that tenant's file-transfer pool, and splits its record onto
     * main — opened at the end of the run, from main's file-transfer pool (decision 5b).
     */
    @Test
    void aTenantsTransferRunsOnItsOwnPoolAndCompletes() throws Exception {
        String transferId = startExport("acme");

        assertThat(awaitTerminal("acme", transferId)).isEqualTo("COMPLETED");
        assertThat(file("acme", transferId)).contains("acme").doesNotContain("public");
    }

    /** Each tenant's pool gains main's roles, sized by main's block or by its own (decision 5a). */
    @Test
    void eachTenantGetsTheRolesSizedByItsOwnBlockOrMains() {
        TenantDataSources tenants = TenantDataSources.load(new AppConfig(Map.of(
                "tenancy", Map.of("mode", "database-per-tenant", "datasources", Map.of(
                        "acme", coordinate("acme"),
                        "globex", with(coordinate("globex"),
                                "jobPool", Map.of("maximumPoolSize", 2, "minimumIdle", 0)))),
                "tesseraql", Map.of("datasources", Map.of("main", with(coordinate(null),
                        "jobPool", Map.of("maximumPoolSize", 3, "minimumIdle", 0),
                        "fileTransferPool", Map.of("maximumPoolSize", 4, "minimumIdle", 0)))))));
        try {
            HikariDataSource acmeJobs = hikari(tenants.dataSourceFor("acme", null, PoolRole.JOBS));
            assertThat(acmeJobs.getPoolName()).isEqualTo("tesseraql-tenant-acme-jobs");
            assertThat(acmeJobs.getMaximumPoolSize()).isEqualTo(3);
            HikariDataSource globexJobs = hikari(tenants.resolve("globex", PoolRole.JOBS));
            assertThat(globexJobs.getPoolName()).isEqualTo("tesseraql-tenant-globex-jobs");
            assertThat(globexJobs.getMaximumPoolSize()).as("the tenant block's own").isEqualTo(2);
            assertThat(hikari(tenants.resolve("acme", PoolRole.FILE_TRANSFERS)).getPoolName())
                    .isEqualTo("tesseraql-tenant-acme-transfers");
            assertThat(hikari(tenants.resolve("acme", PoolRole.ONLINE)).getPoolName())
                    .isEqualTo("tesseraql-tenant-acme");
            assertThatThrownBy(() -> tenants.dataSourceFor("nope", null, PoolRole.JOBS))
                    .isInstanceOf(TqlException.class).hasMessageContaining("TQL-TENANT-4031");
        } finally {
            tenants.close();
        }
    }

    /** With no role declared anywhere, a tenant's job stays on the tenant's own pool. */
    @Test
    void withoutARoleATenantsWorkStaysOnItsOwnPool() {
        TenantDataSources tenants = TenantDataSources.load(new AppConfig(Map.of(
                "tenancy", Map.of("mode", "database-per-tenant",
                        "datasources", Map.of("acme", coordinate("acme"))))));
        try {
            assertThat(hikari(tenants.dataSourceFor("acme", null, PoolRole.JOBS)).getPoolName())
                    .isEqualTo("tesseraql-tenant-acme");
        } finally {
            tenants.close();
        }
    }

    private static HikariDataSource hikari(DataSource pool) {
        assertThat(pool).isInstanceOf(HikariDataSource.class);
        return (HikariDataSource) pool;
    }

    private static Map<String, Object> coordinate(String schema) {
        return Map.of("jdbcUrl", schema == null
                ? POSTGRES.getJdbcUrl()
                : POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                "username", POSTGRES.getUsername(), "password", POSTGRES.getPassword());
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... entries) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(base);
        for (int i = 0; i < entries.length; i += 2) {
            merged.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return merged;
    }

    private static String startExport(String tenant) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/api/export"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        HttpResponse<String> response = HTTP.send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asString();
    }

    private static String awaitTerminal(String tenant, String transferId) throws Exception {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(30);
        while (true) {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create(base() + "/api/export/" + transferId));
            if (tenant != null) {
                request.header("X-Tenant-Id", tenant);
            }
            HttpResponse<String> response = HTTP.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode status = MAPPER.readTree(response.body());
            String state = status.get("status").asString();
            if (!"PENDING".equals(state) && !"RUNNING".equals(state)) {
                return state;
            }
            assertThat(java.time.Instant.now()).as("transfer terminal in time").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    private static String file(String tenant, String transferId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(base() + "/api/export/" + transferId + "/file"));
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        HttpResponse<String> response = HTTP.send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    /** Polls the scrape until {@code pool} holds a connection, or the deadline passes. */
    private static double awaitActive(String pool) throws Exception {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
        double seen = active(pool);
        while (seen <= 0 && java.time.Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            seen = active(pool);
        }
        return seen;
    }

    private static double active(String pool) throws Exception {
        String scrape = HTTP.send(HttpRequest.newBuilder(
                URI.create(base() + "/_tesseraql/metrics")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = Pattern.compile("tesseraql_pool_connections_active\\{pool=\""
                + Pattern.quote(pool) + "\"\\} ([0-9.]+)").matcher(scrape);
        assertThat(matcher.find()).as("the scrape reports " + pool + ":\n" + scrape).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private static String base() {
        return "http://localhost:" + runtime.port();
    }

    private static Path appHome() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-role-pools-it");
        Files.createDirectories(home.resolve("config"));
        String url = POSTGRES.getJdbcUrl();
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tenancy:
                  enabled: true
                  mode: database-per-tenant
                  # A request naming no tenant is served on main, so both of main's roles can be
                  # read beside a tenant's in one runtime.
                  required: false
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

                tesseraql:
                  app:
                    name: role-pools-it
                  metrics:
                    enabled: true
                    unauthenticated: true
                  datasources:
                    main:
                      jdbcUrl: %1$s
                      username: %2$s
                      password: %3$s
                      jobPool:
                        maximumPoolSize: 2
                        minimumIdle: 0
                      fileTransferPool:
                        maximumPoolSize: 2
                        minimumIdle: 0
                """.formatted(url, POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path export = home.resolve("web/api/export");
        Files.createDirectories(export);
        Files.writeString(export.resolve("post.yml"), """
                version: tesseraql/v1
                id: slow.export
                kind: route
                recipe: file-export

                security:
                  auth: public

                export:
                  format: csv
                  filename: schema.csv
                sources:
                  main:
                    sql:
                      file: export.sql
                """);
        // Sleeps in the database, so the transfer holds its connection long enough to be seen,
        // and names the schema it ran in, so the file says whose pool it was.
        Files.writeString(export.resolve("export.sql"),
                "select current_schema() as schema_name from pg_sleep(1.5)\n");

        Path job = home.resolve("batch/slow");
        Files.createDirectories(job);
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: slow
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: slow.sql
                      mode: query
                """);
        Files.writeString(job.resolve("slow.sql"), "select 1 as one from pg_sleep(1.5)\n");
        return home;
    }
}
