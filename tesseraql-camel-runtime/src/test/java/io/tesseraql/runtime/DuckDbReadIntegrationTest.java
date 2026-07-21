package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the duckdb datasource (docs/duckdb.md): CSV and Parquet files read through
 * declared file scopes on an in-process analytics engine beside a real PostgreSQL {@code main},
 * one response composing both — and the connection fence refusing a file outside the scope roots.
 */
@Testcontainers
class DuckDbReadIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    TesseraqlRuntime runtime;
    Path appHome;

    @AfterEach
    void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void readsFilesThroughScopesBesideMainAndFencesTheRest() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // A Parquet aggregation through the ${scope.sales} placeholder.
        HttpResponse<String> parquet = get("/api/sales/summary");
        assertThat(parquet.statusCode()).isEqualTo(200);
        var rows = MAPPER.readTree(parquet.body()).get("data");
        assertThat(rows.get(0).get("category").asText()).isEqualTo("widgets");
        assertThat(rows.get(0).get("total").asLong()).isEqualTo(300);

        // One response composed from main (PostgreSQL) and a CSV read (DuckDB).
        HttpResponse<String> dashboard = get("/api/dashboard");
        assertThat(dashboard.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(dashboard.body());
        assertThat(body.get("open").get(0).get("name").asText()).isEqualTo("main-only");
        assertThat(body.get("drops").get(0).get("region").asText()).isEqualTo("east");

        // The fence: a raw path outside the scope roots is refused by the engine itself,
        // even though the route compiled — defense in depth under the locked configuration.
        Files.writeString(appHome.resolve("secret.csv"), "leak\n1\n");
        HttpResponse<String> outside = get("/api/outside");
        assertThat(outside.statusCode()).isEqualTo(500);

        // A scope name nothing declares fails as TQL-SQL-2111, not a silent empty result.
        HttpResponse<String> ghost = get("/api/ghost");
        assertThat(ghost.statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-duckdb");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  app:
                    name: duckdb-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        memoryLimit: 512MB
                        fileScopes:
                          sales:
                            root: data/sales
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path mainMigrations = home.resolve("db/migration");
        Files.createDirectories(mainMigrations);
        Files.writeString(mainMigrations.resolve("V1__create_orders.sql"),
                "create table open_orders (id serial primary key, name varchar(100) not null);\n"
                        + "insert into open_orders (name) values ('main-only');\n");

        Path drops = home.resolve("data/sales");
        Files.createDirectories(drops);
        Files.writeString(drops.resolve("drops.csv"), "region,amount\neast,10\nwest,20\n");
        // The Parquet fixture is produced by the engine itself, on an unfenced test connection.
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = duck.createStatement()) {
            statement.execute("""
                    COPY (
                      SELECT * FROM (VALUES
                        ('widgets', 300), ('gadgets', 120)
                      ) AS t(category, total)
                    ) TO '%s' (FORMAT parquet)
                    """.formatted(drops.resolve("monthly.parquet")));
        }

        Path summaryRoute = home.resolve("web/api/sales/summary");
        Files.createDirectories(summaryRoute);
        Files.writeString(summaryRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: sales.summary
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: summary.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(summaryRoute.resolve("summary.sql"), """
                select category, sum(total) as total
                from read_parquet(/* ${scope.sales}/monthly.parquet */ 'dummy.parquet')
                group by category
                order by total desc
                """);

        Path dashboardRoute = home.resolve("web/api/dashboard");
        Files.createDirectories(dashboardRoute);
        Files.writeString(dashboardRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: dashboard.view
                kind: route
                recipe: query-json
                sql:
                  file: open.sql
                  mode: query
                queries:
                  drops:
                    file: drops.sql
                    mode: query
                    datasource: analytics
                response:
                  json:
                    body:
                      open: sql.rows
                      drops: drops.rows
                """);
        Files.writeString(dashboardRoute.resolve("open.sql"),
                "select name from open_orders order by id\n;\n");
        Files.writeString(dashboardRoute.resolve("drops.sql"), """
                select region, amount
                from read_csv(/* ${scope.sales}/drops.csv */ 'dummy.csv')
                order by region
                """);

        Path outsideRoute = home.resolve("web/api/outside");
        Files.createDirectories(outsideRoute);
        Files.writeString(outsideRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: outside.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: outside.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(outsideRoute.resolve("outside.sql"),
                "select * from read_csv('" + home.resolve("secret.csv") + "')\n");

        Path ghostRoute = home.resolve("web/api/ghost");
        Files.createDirectories(ghostRoute);
        Files.writeString(ghostRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: ghost.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: ghost.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(ghostRoute.resolve("ghost.sql"),
                "select * from read_csv(/* ${scope.ghost}/x.csv */ 'dummy.csv')\n");
        return home;
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
