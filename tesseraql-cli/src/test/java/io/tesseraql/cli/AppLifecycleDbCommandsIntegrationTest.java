package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

/**
 * The database-backed app-lifecycle CLI surface — {@code migrate} (apply/info/validate),
 * {@code test --report}, {@code coverage}, {@code schema} and {@code identity-schema} — drives the
 * same engines as the Maven goals against PostgreSQL, over a freshly scaffolded app.
 */
@Testcontainers
class AppLifecycleDbCommandsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrateTestCoverageSchemaAndIdentityOverPostgres(@TempDir Path dir) {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        Path app = dir.resolve("demo");

        // Apply, then the read-only operations over the same per-app history.
        assertThat(execute(args(app, "migrate", "apply"))).isZero();
        assertThat(execute(args(app, "migrate", "validate"))).isZero();
        assertThat(execute(args(app, "migrate", "info"))).isZero();

        // The smoke suite runs over the migrated, seeded database; --report writes the overlay.
        assertThat(execute(args(app, "test"))).isZero();
        assertThat(execute(args(app, "test", "--report"))).isZero();
        assertThat(app.resolve(".tesseraql/docs/report.json")).exists();
        assertThat(app.resolve(".tesseraql/docs/history.json")).exists();

        // Coverage gate (default thresholds are 0, so it passes).
        assertThat(execute(args(app, "coverage"))).isZero();

        // Schema overlay from the live catalog.
        assertThat(execute(args(app, "schema"))).isZero();
        assertThat(app.resolve(".tesseraql/docs/schema.json")).exists();

        // The managed IAM schema applies idempotently.
        assertThat(execute(args(app, "identity-schema"))).isZero();
        assertThat(execute(args(app, "identity-schema"))).isZero();
    }

    /** A command (with any positional) plus {@code --app} and the container's datasource flags. */
    private String[] args(Path app, String... command) {
        return Stream.concat(Stream.of(command),
                Stream.of("--app", app.toString(),
                        "--jdbc-url", POSTGRES.getJdbcUrl(),
                        "--username", POSTGRES.getUsername(),
                        "--password", POSTGRES.getPassword()))
                .toArray(String[]::new);
    }

    private static int execute(String... args) {
        return new CommandLine(new TesseraqlCli()).execute(args);
    }
}
