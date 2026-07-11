package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.apptasks.IdentityBootstrap;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.runtime.DataSources;
import io.tesseraql.runtime.TesseraqlRuntime;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Exercises the {@code serve --embedded-db} machinery end to end: the platform PostgreSQL binary is
 * resolved on demand and a real embedded instance backs the runtime's {@code main} datasource. The
 * test resolves and runs an actual {@code postgres} process (the linux-amd64 binary is on the test
 * classpath so resolution stays offline), so it is heavier than a unit test.
 */
class EmbeddedDbServeIntegrationTest {

    @Test
    void embeddedDbBootsTheRuntimeAndMigratesAgainstRealPostgres(@TempDir Path dir)
            throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, false);
        TesseraqlRuntime runtime = null;
        try {
            String jdbcUrl = embedded.override().jdbcUrl();
            assertThat(jdbcUrl).startsWith("jdbc:postgresql://");

            runtime = TesseraqlRuntime.start(app, freePort(), embedded.override());
            assertThat(runtime.port()).isPositive();

            // It is a real postgres, so the framework's Flyway + ensureSchema bootstrap and the
            // app's own db/migration both ran — no dialect-specific work was needed.
            assertThat(tableExists(jdbcUrl, "tql_outbox_event")).isTrue();
            assertThat(tableExists(jdbcUrl, "items")).isTrue();
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            embedded.close();
        }
    }

    @Test
    void servesTheBundledLoginPage(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, false);
        TesseraqlRuntime runtime = null;
        try {
            runtime = TesseraqlRuntime.start(app, freePort(), embedded.override());

            // The bundled auth-ui app mounts by default and serves a public password login form.
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + runtime.port()
                                    + "/_tesseraql/login"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("name=\"loginId\"")
                    .contains("action=\"/_tesseraql/login\"");
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            embedded.close();
        }
    }

    @Test
    void browserAuthRedirectsUnauthenticatedNavigationAndAcceptsASessionCookie(@TempDir Path dir)
            throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, false);
        TesseraqlRuntime runtime = null;
        try {
            runtime = TesseraqlRuntime.start(app, freePort(), embedded.override());
            String base = "http://localhost:" + runtime.port();
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER).build();

            // A browser opening a protected Studio page with no session is bounced to the login page
            // (post/redirect/get), carrying the original target as ?next=.
            HttpResponse<Void> unauth = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(base + "/_tesseraql/studio/ui")).header("Accept", "text/html")
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(unauth.statusCode()).isEqualTo(302);
            assertThat(unauth.headers().firstValue("location").orElseThrow())
                    .startsWith("/_tesseraql/login?next=");

            // A valid browser session (any of password/OIDC/SAML would create one) grants access.
            io.tesseraql.security.session.SessionStore sessions = runtime.camelContext()
                    .getRegistry()
                    .lookupByNameAndType(io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                            io.tesseraql.security.session.SessionStore.class);
            String sid = sessions.create(new io.tesseraql.security.Principal("dev", "dev", "Dev",
                    null, java.util.List.of(), java.util.List.of("ADMIN"), java.util.List.of(),
                    java.util.Map.of()));
            HttpResponse<Void> authed = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(base + "/_tesseraql/studio/ui"))
                    .header("Cookie", sessions.cookieName() + "=" + sid)
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(authed.statusCode()).isEqualTo(200);
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            embedded.close();
        }
    }

    @Test
    void mountsStudioAndRedirectsTheBarePathToTheUiLanding(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, false);
        TesseraqlRuntime runtime = null;
        try {
            runtime = TesseraqlRuntime.start(app, freePort(), embedded.override());

            // The bundled Studio app mounts via the ServiceLoader; the bare /_tesseraql/studio is a
            // public alias that 302-redirects to the real UI landing under /ui (which requires a
            // browser session). Without following the redirect we see the alias's own response.
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(HttpRequest.newBuilder()
                            .uri(URI.create(
                                    "http://localhost:" + runtime.port() + "/_tesseraql/studio"))
                            .GET().build(), HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location"))
                    .hasValue("/_tesseraql/studio/ui");
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            embedded.close();
        }
    }

    @Test
    void persistentDataDirectorySurvivesARestart(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("pgdata");

        EmbeddedPostgresSupport.Handle first = EmbeddedPostgresSupport.start(dataDir, false);
        try (Connection connection = DriverManager.getConnection(first.override().jdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("create table keep(id int primary key)");
            statement.execute("insert into keep(id) values (42)");
        } finally {
            first.close();
        }

        EmbeddedPostgresSupport.Handle second = EmbeddedPostgresSupport.start(dataDir, false);
        try (Connection connection = DriverManager.getConnection(second.override().jdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select id from keep")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt("id")).isEqualTo(42);
        } finally {
            second.close();
        }
    }

    @Test
    void persistentStartRecordsAndReResolvesItsVersion(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("pgdata");

        // A fresh persistent start pins the directory to the version that initialized it.
        EmbeddedPostgresSupport.Handle first = EmbeddedPostgresSupport.start(dataDir, false);
        String version = first.version();
        first.close();
        assertThat(EmbeddedPostgresDataDir.pinnedVersion(dataDir)).contains(version);
        assertThat(EmbeddedPostgresDataDir.isInitialized(dataDir)).isTrue();

        // A later start with an explicit but incompatible-typed request still honours the pin: the
        // directory carries a marker, so passing null re-resolves exactly the recorded version.
        EmbeddedPostgresSupport.Handle second = EmbeddedPostgresSupport.start(dataDir, false);
        try {
            assertThat(second.version()).isEqualTo(version);
        } finally {
            second.close();
        }
    }

    @Test
    void incompatibleMajorIsRejectedBeforeStart(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("pgdata");

        // Initialize a real directory (major 17, the test-classpath binary).
        EmbeddedPostgresSupport.start(dataDir, false).close();
        String onDiskMajor = EmbeddedPostgresDataDir.onDiskMajor(dataDir).orElseThrow();

        // Forcing an incompatible major fails fast with a guiding message — and before any attempt
        // to resolve the (uncached, network-only) 18.x binary, so the check itself stays offline.
        assertThatThrownBy(
                () -> EmbeddedPostgresSupport.start(dataDir, null, "18.4.0", true))
                .isInstanceOf(EmbeddedPostgresVersionMismatchException.class)
                .hasMessageContaining(onDiskMajor)
                .hasMessageContaining("18.4.0")
                .hasMessageContaining("--embedded-db-version");
    }

    @Test
    void fixedPortBindsTheRequestedTcpPort(@TempDir Path dir) throws Exception {
        int port = freePort();

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, port, false);
        try {
            assertThat(embedded.port()).isEqualTo(port);
            assertThat(embedded.jdbcUrl()).contains(":" + port + "/");
            // Reachable at exactly that address over the loopback trust connection.
            try (Connection connection = DriverManager.getConnection(embedded.jdbcUrl())) {
                assertThat(connection.isValid(5)).isTrue();
            }
        } finally {
            embedded.close();
        }
    }

    @Test
    void identitySchemaPicksUpTheRunningEmbeddedDatabaseMarker(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));
        // Point the app's configured database at a port nothing answers on — the situation a
        // `serve --embedded-db` user is in (no compose database running).
        writeUnreachableMainDatasource(app);

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, false);
        try {
            // What `serve --embedded-db` leaves behind at startup: the hand-off marker.
            EmbeddedDbMarker.write(app, embedded.jdbcUrl());
            Path passwordFile = dir.resolve("admin.pw");
            Files.writeString(passwordFile, "change-me");

            Captured result = executeCapturing("identity-schema", "--app", app.toString(),
                    "--admin-login", "admin", "--admin-password-file", passwordFile.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout())
                    .contains("Using the running embedded database (work/embedded-db.jdbc)")
                    .contains("Applied the managed IAM schema (postgres)")
                    .contains("Seeded administrator 'admin'");
            // The schema and the administrator landed in the running embedded database.
            try (Connection connection = DriverManager.getConnection(embedded.jdbcUrl());
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "select count(*) from tql_users where login_id = 'admin'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        } finally {
            embedded.close();
        }
    }

    @Test
    void aStaleMarkerNeverBacksIdentitySchema(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));
        writeUnreachableMainDatasource(app);
        // A marker left by a crashed serve: its database no longer answers.
        EmbeddedDbMarker.write(app, "jdbc:postgresql://localhost:" + freePort() + "/postgres");

        Captured result = executeCapturing("identity-schema", "--app", app.toString());

        // The marker fails the freshness probe, so resolution falls back to the configured
        // datasource (which is down) — the command fails as before, without claiming the marker.
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).doesNotContain("Using the running embedded database");
    }

    @Test
    void firstAdminHintShowsUntilAnAdministratorExists(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));

        EmbeddedPostgresSupport.Handle embedded = EmbeddedPostgresSupport.start(null, false);
        try {
            AppConfig config = new ManifestLoader().load(app).config();

            // The identity schema was never applied: the hint shows, with no URL to hand-copy.
            assertThat(FirstAdminHint.check(config, app, embedded.override()))
                    .hasValueSatisfying(hint -> assertThat(hint)
                            .contains("No users exist yet")
                            .contains("tesseraql identity-schema --app " + app
                                    + " --admin-login admin --admin-password-file <file>"));

            // Applied but still empty: still the hint.
            IdentityBootstrap bootstrap = new IdentityBootstrap(
                    new DriverManagerDataSource(embedded.jdbcUrl(), null, null));
            bootstrap.applySchema("postgres");
            assertThat(FirstAdminHint.check(config, app, embedded.override())).isPresent();

            // With the password login form switched off the managed realm is not a login path,
            // so the hint stays quiet even though the store is empty.
            Files.writeString(app.resolve("config/tesseraql.yml"),
                    "\n  console:\n    login:\n      password:\n        enabled: false\n",
                    StandardOpenOption.APPEND);
            AppConfig passwordLoginOff = new ManifestLoader().load(app).config();
            assertThat(FirstAdminHint.check(passwordLoginOff, app, embedded.override())).isEmpty();

            // A seeded administrator silences it for good.
            bootstrap.seedAdmin("admin", "change-me", List.of("iam.admin"), List.of());
            assertThat(FirstAdminHint.check(config, app, embedded.override())).isEmpty();

            // An unreachable database never fails the check — it just stays quiet.
            assertThat(FirstAdminHint.check(config, app, new DataSources.MainDatasourceOverride(
                    "jdbc:postgresql://localhost:" + freePort() + "/nope", null, null))).isEmpty();
        } finally {
            embedded.close();
        }
    }

    /** Rewrites the scaffolded {@code db.main.*} inputs to a port nothing listens on. */
    private static void writeUnreachableMainDatasource(Path app) throws IOException {
        Files.writeString(app.resolve("config/application.yml"), """
                server:
                  port: 8080

                db:
                  main:
                    url: jdbc:postgresql://localhost:%d/demo
                    username: demo
                    password: demo
                """.formatted(freePort()));
    }

    private static Captured executeCapturing(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new TesseraqlCli()).execute(args);
            return new Captured(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Captured(int exitCode, String stdout) {
    }

    private static boolean tableExists(String jdbcUrl, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
