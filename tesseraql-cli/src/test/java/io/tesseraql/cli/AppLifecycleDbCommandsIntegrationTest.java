package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import picocli.CommandLine;

/**
 * The database-backed app-lifecycle CLI surface — {@code migrate} (apply/info/validate),
 * {@code test --report}, {@code coverage}, {@code schema} and {@code identity-schema} — drives the
 * same engines as the Maven goals against PostgreSQL, over a freshly scaffolded app.
 */
@Testcontainers
class AppLifecycleDbCommandsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migrateTestCoverageSchemaAndIdentityOverPostgres(@TempDir Path dir) throws Exception {
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

        // The editor test-run contract (Phase 55): complete per-case results plus per-file
        // SQL coverage with the 1-based covered/coverable line lists, one JSON object.
        Captured json = executeCapturing(args(app, "test", "--format", "json"));
        assertThat(json.exitCode()).isZero();
        JsonNode document = new ObjectMapper().readTree(json.stdout());
        assertThat(document.get("failed").asLong()).isZero();
        assertThat(document.get("passed").asLong()).isEqualTo(document.get("results").size());
        assertThat(document.get("results").get(0).get("name").asText()).isNotBlank();
        assertThat(document.get("results").get(0).get("passed").asBoolean()).isTrue();
        assertThat(document.get("sql").size()).isPositive();
        JsonNode sqlFile = document.get("sql").get(0);
        assertThat(sqlFile.get("file").asText()).endsWith(".sql");
        assertThat(sqlFile.get("coveredLines").isArray()).isTrue();
        assertThat(sqlFile.get("coverableLines").isArray()).isTrue();

        // Single-case granularity (Phase 56): --case runs exactly the named case.
        Captured single = executeCapturing(args(app, "test", "--format", "json",
                "--case", "the items search returns the seeded row"));
        assertThat(single.exitCode()).isZero();
        JsonNode filtered = new ObjectMapper().readTree(single.stdout());
        assertThat(filtered.get("results").size()).isEqualTo(1);
        assertThat(filtered.get("results").get(0).get("name").asText())
                .isEqualTo("the items search returns the seeded row");

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

    private static Captured executeCapturing(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = execute(args);
            return new Captured(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Captured(int exitCode, String stdout) {
    }
}
