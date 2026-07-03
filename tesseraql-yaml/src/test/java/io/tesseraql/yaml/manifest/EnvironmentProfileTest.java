package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The environment profile layer (roadmap Phase 46): {@code config/env/<profile>.yml} merges
 * between the app's base config and Studio's overlay — the profile is the environment's
 * tuning, dev-time Studio edits still win — selected by one switch and failing fast when the
 * named profile has no file.
 */
class EnvironmentProfileTest {

    @AfterEach
    void clearProfile() {
        System.clearProperty("tesseraql.env");
    }

    private Path app(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config/env"));
        Files.writeString(dir.resolve("config/application.yml"), """
                tesseraql:
                  app:
                    name: profiled
                  sql:
                    timeoutSeconds: 30
                """);
        Files.writeString(dir.resolve("config/env/staging.yml"), """
                tesseraql:
                  sql:
                    timeoutSeconds: 5
                  metrics:
                    enabled: true
                """);
        return dir;
    }

    @Test
    void profileMergesBetweenBaseAndOverlay(@TempDir Path dir) throws Exception {
        Path home = app(dir);
        System.setProperty("tesseraql.env", "staging");

        var config = new ManifestLoader().load(home).config();
        // The profile overrides the base...
        assertThat(config.getString("tesseraql.sql.timeoutSeconds")).contains("5");
        assertThat(config.getString("tesseraql.metrics.enabled")).contains("true");
        // ...but keys it does not touch survive from the base.
        assertThat(config.getString("tesseraql.app.name")).contains("profiled");

        // Studio's overlay stays the last word on top of the profile.
        Files.writeString(home.resolve("config/overlay.yml"), """
                tesseraql:
                  sql:
                    timeoutSeconds: 7
                """);
        var overlaid = new ManifestLoader().load(home).config();
        assertThat(overlaid.getString("tesseraql.sql.timeoutSeconds")).contains("7");
        assertThat(overlaid.getString("tesseraql.metrics.enabled")).contains("true");
    }

    @Test
    void noProfileKeepsTodayBehaviorAndAMissingOrInvalidOneFailsFast(@TempDir Path dir)
            throws Exception {
        Path home = app(dir);
        assertThat(new ManifestLoader().load(home).config()
                .getString("tesseraql.sql.timeoutSeconds")).contains("30");

        System.setProperty("tesseraql.env", "prod");
        assertThatThrownBy(() -> new ManifestLoader().load(home))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("config/env/prod.yml does not exist");

        System.setProperty("tesseraql.env", "../evil");
        assertThatThrownBy(() -> new ManifestLoader().load(home))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Invalid environment profile name");
    }
}
