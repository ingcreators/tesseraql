package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.runtime.TesseraqlRuntime;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
