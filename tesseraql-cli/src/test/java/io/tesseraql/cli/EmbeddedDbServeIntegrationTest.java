package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.runtime.TesseraqlRuntime;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
import java.net.ServerSocket;
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
