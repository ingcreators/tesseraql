package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot's TQL-SEC-4154 at lint (docs/deployment-decisions.md S4): under a named profile, a JWT
 * secret that is the development secret the scaffold publishes, read from the declaration with
 * nothing from the environment. The kind proof's probe application linted clean under
 * {@code --env kind} while both its replicas refused to start: its profile inherited the base
 * configuration's fallback.
 */
class AppLinterDevelopmentSecretTest {

    private static final String DEVELOPMENT = AppScaffolder.DEVELOPMENT_JWT_SECRET;

    @AfterEach
    void clearProfile() {
        System.clearProperty("tesseraql.env");
    }

    /** The generated base configuration's shape, and a {@code prod} profile of {@code prodProfile}. */
    private static Path app(Path dir, String prodProfile) throws Exception {
        Files.createDirectories(dir.resolve("config/env"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    jwt:
                      secret: ${JWT_SECRET:%s}
                      audience: https://t.example.com
                """.formatted(DEVELOPMENT));
        Files.writeString(dir.resolve("config/env/prod.yml"), prodProfile);
        return dir;
    }

    private static String jwtSecret(String secret) {
        return """
                tesseraql:
                  security:
                    jwt:
                      secret: %s
                """.formatted(secret);
    }

    private static List<LintFinding> developmentSecret(Path app) {
        return new AppLinter().lint(app).stream()
                .filter(finding -> "TQL-SEC-4154".equals(finding.code()))
                .toList();
    }

    @Test
    void aProfileThatInheritsTheFallbackIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, "tesseraql:\n  metrics:\n    enabled: true\n");
        System.setProperty("tesseraql.env", "prod");

        List<LintFinding> findings = developmentSecret(app);

        assertThat(findings).extracting(LintFinding::severity).containsExactly("error");
        assertThat(findings.get(0).message())
                .contains("falls back to, the development secret")
                .contains("the 'prod' profile is active")
                .contains("config/env/prod.yml");
    }

    @Test
    void theLiteralUnderAProfileIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, jwtSecret(DEVELOPMENT));
        System.setProperty("tesseraql.env", "prod");

        assertThat(developmentSecret(app)).extracting(LintFinding::severity)
                .containsExactly("error");
    }

    @Test
    void aSecretProviderThatFallsBackToItIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, jwtSecret("${secret.file.jwt:" + DEVELOPMENT + "}"));
        System.setProperty("tesseraql.env", "prod");

        assertThat(developmentSecret(app)).hasSize(1);
    }

    /** The generated profiles' shape: the environment must supply it, and nothing falls back. */
    @Test
    void aProfileThatTakesTheSecretFromTheEnvironmentIsQuiet(@TempDir Path dir) throws Exception {
        System.setProperty("tesseraql.env", "prod");

        assertThat(developmentSecret(app(dir.resolve("env"), jwtSecret("${JWT_SECRET}"))))
                .isEmpty();
        assertThat(developmentSecret(app(dir.resolve("file"), jwtSecret("${secret.file.jwt}"))))
                .isEmpty();
        assertThat(developmentSecret(
                app(dir.resolve("own"), jwtSecret("${JWT_SECRET:a-key-of-this-environment}"))))
                .isEmpty();
    }

    /** Development runs without a profile, and its fallback is the point. */
    @Test
    void withoutAProfileTheFallbackIsQuiet(@TempDir Path dir) throws Exception {
        assertThat(developmentSecret(app(dir, "tesseraql: {}\n"))).isEmpty();
    }
}
