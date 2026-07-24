package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints around the app-wide default response headers (docs/route-defaults.md). */
class AppLinterResponseHeadersTest {

    private Path app(@TempDir Path dir, String routeHeaders) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      X-Frame-Options: DENY
                      Content-Security-Policy: "default-src 'self'"
                """);
        Files.createDirectories(dir.resolve("web/home"));
        Files.writeString(dir.resolve("web/home/get.yml"), """
                version: tesseraql/v1
                id: home.page
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sql:
                  file: home.sql
                response:
                  html:
                    template: home.html
                %s
                """.formatted(routeHeaders));
        Files.writeString(dir.resolve("web/home/home.sql"), "select 1\n");
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/home.html"), "<main></main>\n");
        return dir;
    }

    @Test
    void flagsAnIdenticalRestatement(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      X-Frame-Options: DENY"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4133") && !f.isError()
                && f.source().equals("web/home/get.yml"));
    }

    @Test
    void flagsSuppressionAndWildcardBroadening(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      X-Frame-Options: unset\n"
                        + "      Content-Security-Policy: \"default-src *\""));

        assertThat(findings.stream()
                .filter(f -> f.code().equals("TQL-SEC-4134")).count()).isEqualTo(2);
    }

    @Test
    void aDifferentiatedOverrideAndForeignHeadersLintClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n"
                        + "      Content-Security-Policy: \"default-src 'self'; frame-src 'self' data:\"\n"
                        + "      HX-Trigger: saved"));

        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-SEC-413"));
    }
}
