package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stack's own settings file — {@code tesseraql-stack.yml} in the directory {@code --stack}
 * names (docs/stack-architecture.md Decision 22): loaded through the same configuration
 * machinery applications use, optional in every part, and a marker even when it says nothing.
 */
class StackSettingsTest {

    /**
     * The interval takes the framework's short duration form — {@code 30s}, {@code 5m}, or a
     * plain number of seconds — like every other duration key; it used to require ISO-8601,
     * the one duration key in the tree with its own grammar. Unparseable still yields empty
     * (the caller's default), the recorded safe direction for a safety net.
     */
    @Test
    void theReconcileIntervalSpeaksTheFrameworkDurationForm(@TempDir Path dir)
            throws IOException {
        assertThat(withInterval(dir, "30s").reconcileInterval())
                .contains(java.time.Duration.ofSeconds(30));
        assertThat(withInterval(dir, "5m").reconcileInterval())
                .contains(java.time.Duration.ofMinutes(5));
        assertThat(withInterval(dir, "45").reconcileInterval())
                .contains(java.time.Duration.ofSeconds(45));
        assertThat(withInterval(dir, "0").reconcileInterval())
                .contains(java.time.Duration.ZERO);
        assertThat(withInterval(dir, "not-a-duration").reconcileInterval()).isEmpty();
    }

    private static StackSettings withInterval(Path dir, String interval) throws IOException {
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), """
                stack:
                  reconcile:
                    interval: "%s"
                """.formatted(interval));
        return StackSettings.load(dir);
    }

    @Test
    void theFrameworkDatasourceIsACoordinateNotAName(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), """
                framework:
                  datasource:
                    jdbcUrl: jdbc:postgresql://db:5432/stack
                    username: stack
                    password: secret
                externalOrigin: https://apps.example.com
                root:
                  redirect: orders
                """);

        StackSettings settings = StackSettings.load(dir);

        assertThat(settings.frameworkDatasource()).hasValueSatisfying(coordinate -> {
            assertThat(coordinate.jdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/stack");
            assertThat(coordinate.username()).isEqualTo("stack");
            assertThat(coordinate.password()).isEqualTo("secret");
        });
        assertThat(settings.externalOrigin()).contains("https://apps.example.com");
        assertThat(settings.rootRedirect()).contains("orders");
    }

    /** Placeholders resolve exactly as they do in an application's own configuration. */
    @Test
    void placeholdersResolveWithTheirDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), """
                framework:
                  datasource:
                    jdbcUrl: jdbc:postgresql://${TQL_TEST_ABSENT_HOST:localhost}:5432/stack
                """);

        assertThat(StackSettings.load(dir).frameworkDatasource())
                .hasValueSatisfying(coordinate -> assertThat(coordinate.jdbcUrl())
                        .isEqualTo("jdbc:postgresql://localhost:5432/stack"));
    }

    /**
     * Absence of any part is ordinary — the file {@code new} generates is all guidance comments,
     * a marker that supplies nothing — so every downstream check keys on what is supplied, never
     * on whether the file exists.
     */
    @Test
    void aMarkerThatSuppliesNothingLoadsAsEmpty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), "# just the marker\n");

        StackSettings settings = StackSettings.load(dir);

        assertThat(settings.frameworkDatasource()).isEmpty();
        assertThat(settings.externalOrigin()).isEmpty();
    }

    @Test
    void noFileLoadsAsEmpty(@TempDir Path dir) {
        StackSettings settings = StackSettings.load(dir);

        assertThat(settings.frameworkDatasource()).isEmpty();
        assertThat(settings.externalOrigin()).isEmpty();
    }
}
