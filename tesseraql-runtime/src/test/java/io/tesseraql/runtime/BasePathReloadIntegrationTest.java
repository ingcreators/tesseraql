package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A hot reload keeps the address the runtime is served under
 * (docs/base-path-emission.md decision 9).
 *
 * <p>{@code base-path.md} puts per-request base paths out of scope: "the prefix is fixed for a
 * runtime's lifetime." A reload re-reads the manifest from disk, and the disk copy never carried
 * the prefix — the host injected it in memory at start. So without care the rule holds until
 * somebody saves a file, which is the one thing {@code dev --watch} exists to make people do.
 *
 * <p>Its own test class because it runs a file watcher, which the emission crawl must not have.
 */
@Testcontainers
class BasePathReloadIntegrationTest {

    private static final String PREFIX = "/shop";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static final List<String> WATCH_LINES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        try (com.zaxxer.hikari.HikariDataSource migration = DataSources.create(
                "tesseraql-base-path-reload-migration",
                new DataSources.MainDatasourceOverride(POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(), POSTGRES.getPassword()))) {
            FrameworkMigrations.migrateSecurity(migration);
        }
        runtime = TesseraqlRuntime.start(appHome, 0,
                HostContext.stack().forApplication(PREFIX));
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("user-1", "user-1", "User", null,
                List.of(), List.of("USER"), List.of("tql.app.use.base-path-reload-app"),
                Map.of()), SessionStore.ClientInfo.NONE);
        cookie = sessions.cookieName() + "=" + sid;
        runtime.watchRoutes(WATCH_LINES::add);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aReloadedRouteStillEmitsTheAddressItIsServedUnder() throws Exception {
        // The shell's own asset links are the cheapest witness: they go through the link
        // builder, so they carry the prefix on any page this runtime renders.
        assertThat(body("/things")).contains(PREFIX + "/assets/_tesseraql/tesseraql.css");

        // Save the route's SQL the way an editor does. The watcher debounces and reloads.
        Files.writeString(appHome.resolve("web/things/list.sql"),
                "select id, name from things order by id desc\n;\n");
        awaitReload();

        // The prefix is fixed for a runtime's lifetime, saves included.
        assertThat(body("/things")).contains(PREFIX + "/assets/_tesseraql/tesseraql.css")
                .doesNotContain("href=\"/assets/_tesseraql/tesseraql.css\"");
    }

    private static void awaitReload() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (WATCH_LINES.stream().anyMatch(line -> line.contains("reloaded routes"))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the watcher never reported a reload: " + WATCH_LINES);
    }

    private static String body(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).build()
                .send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + PREFIX + path))
                        .header("Cookie", cookie).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return response.body();
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table things (id int primary key,"
                    + " name varchar(100) not null)");
            statement.execute("insert into things (id, name) values (1, 'Alpha'), (2, 'Beta')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-base-path-reload-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: base-path-reload-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    defaults:
                      routes:
                        - match: /**
                          auth: browser
                          csrf: auto
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.createDirectories(home.resolve("web/things"));
        Files.writeString(home.resolve("web/things/get.yml"), """
                version: tesseraql/v1
                id: things.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  html:
                    view: things
                """);
        Files.writeString(home.resolve("web/things/list.sql"),
                "select id, name from things order by id\n;\n");
        Files.writeString(home.resolve("web/things/list.view.yml"), """
                version: tesseraql/v1
                id: things
                kind: view
                recipe: list
                key: id
                title: Things
                columns:
                  - { name: id, label: "#" }
                  - { name: name }
                """);
        return home;
    }
}
