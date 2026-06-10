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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for app-mounted Flyway migrations (design ch. 31, 32): db/migration applies at
 * boot for the main app and mounted apps (per-app history tables), runs per tenant pool in
 * schema-per-tenant mode, and re-mounting the same version is idempotent.
 */
@Testcontainers
class AppMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    TesseraqlRuntime runtime;
    Path appHome;
    Path mountedHome;

    @AfterEach
    void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        for (Path root : new Path[] {appHome, mountedHome}) {
            if (root != null) {
                deleteRecursively(root);
            }
        }
    }

    @Test
    void migratesMainAndMountedAppsIdempotently() throws Exception {
        mountedHome = prepareMountedApp();
        appHome = prepareMainApp(mountedHome);

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The main app's two migrations created and seeded its table; the route can query it.
        HttpResponse<String> items = get("/api/items");
        assertThat(items.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(items.body()).get("data").get(0).get("name").asText())
                .isEqualTo("seeded");

        // The mounted app migrated its own table under its own history table.
        HttpResponse<String> notes = get("/sysapp/notes");
        assertThat(notes.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(notes.body()).get("data").get(0).get("note").asText())
                .isEqualTo("mounted");
        assertThat(historyCount("tql_schema_history_demo_app")).isEqualTo(2);
        assertThat(historyCount("tql_schema_history_sysapp")).isEqualTo(1);

        // Re-mounting the same version applies nothing new (idempotent boot).
        runtime.close();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        assertThat(get("/api/items").statusCode()).isEqualTo(200);
        assertThat(historyCount("tql_schema_history_demo_app")).isEqualTo(2);
    }

    @Test
    void migratesEveryTenantPoolInSchemaPerTenantMode() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists tenant_a");
            statement.execute("create schema if not exists tenant_b");
        }
        appHome = prepareTenantApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The same migration ran once per tenant schema.
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String schema : new String[] {"tenant_a", "tenant_b"}) {
                try (ResultSet rs = statement.executeQuery(
                        "select count(*) from " + schema + ".tenant_items")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    private static int historyCount(String table) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        // Exclude the Flyway baseline marker row (version 0) inserted because the
                        // schema already holds the framework's tql_* tables.
                        "select count(*) from " + table
                                + " where version is not null and version <> '0'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareMainApp(Path mounted) throws IOException {
        Path home = Files.createTempDirectory("tesseraql-migration-main");
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
                    name: demo-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                  apps:
                    sysapp:
                      path: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), mounted));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__create_items.sql"),
                "create table items (id serial primary key, name varchar(100) not null);\n");
        Files.writeString(migrations.resolve("V2__seed_items.sql"),
                "insert into items (name) values ('seeded');\n");
        Path route = home.resolve("web/api/items");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(route.resolve("list.sql"), "select name from items order by id\n;\n");
        return home;
    }

    private static Path prepareMountedApp() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-migration-mounted");
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__create_notes.sql"),
                "create table sysapp_notes (id serial primary key, note varchar(100) not null);\n"
                        + "insert into sysapp_notes (note) values ('mounted');\n");
        Path route = home.resolve("web/sysapp/notes");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: sysapp.notes
                kind: route
                recipe: query-json
                sql:
                  file: notes.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(route.resolve("notes.sql"), "select note from sysapp_notes\n;\n");
        return home;
    }

    private static Path prepareTenantApp() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-migration-tenant");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tenancy:
                  enabled: true
                  mode: schema-per-tenant
                  datasources:
                    tenant-a:
                      jdbcUrl: %s&currentSchema=tenant_a
                      username: %s
                      password: %s
                    tenant-b:
                      jdbcUrl: %s&currentSchema=tenant_b
                      username: %s
                      password: %s

                tesseraql:
                  app:
                    name: tenant-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__create_tenant_items.sql"),
                "create table tenant_items (id serial primary key, name varchar(100) not null);\n"
                        + "insert into tenant_items (name) values ('per-tenant');\n");
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
