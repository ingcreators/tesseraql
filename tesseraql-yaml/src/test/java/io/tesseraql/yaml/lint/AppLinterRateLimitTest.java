package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** rateLimit.scope lints (docs/deployment.md, "Cluster rate limits"). */
class AppLinterRateLimitTest {

    private static void writeApp(Path dir, String scope) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/limited"));
        Files.writeString(dir.resolve("web/limited/limited.sql"), "select 1 as ok\n");
        Files.writeString(dir.resolve("web/limited/get.yml"), """
                version: tesseraql/v1
                id: limited.get
                kind: route
                recipe: query-json
                policy:
                  rateLimit:
                    requestsPerSecond: 5
                    scope: %s
                sql:
                  file: limited.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """.formatted(scope));
    }

    @Test
    void nodeAndClusterScopesLintClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "cluster");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
        writeApp(dir, "node");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void anUnknownScopeIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "global");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1023".equals(finding.code()));
    }
}
