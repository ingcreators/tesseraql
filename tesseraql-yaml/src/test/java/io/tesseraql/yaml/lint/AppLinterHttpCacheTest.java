package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** cache: lints (docs/response-shaping.md, "HTTP caching") — TQL-YAML-1025. */
class AppLinterHttpCacheTest {

    private static void writeApp(Path dir, String recipe, String auth, String cache)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: %s
                security:
                  auth: %s
                cache:
                %s
                sql:
                  file: orders.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """.formatted(recipe, auth, cache));
    }

    @Test
    void privateCachingOnAnAuthenticatedQueryLintsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "bearer", "  maxAge: 30s");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void publicVisibilityRequiresAPublicRoute(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "bearer", "  maxAge: 30s\n  visibility: public");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1025".equals(finding.code())
                && finding.message().contains("per-principal"));

        writeApp(dir, "query-json", "public", "  maxAge: 30s\n  visibility: public");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void badDurationsAndNonQueryRecipesAreErrors(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "bearer", "  maxAge: whenever");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && finding.message().contains("unparseable duration"));

        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.approve
                kind: route
                recipe: command-json
                cache:
                  maxAge: 30s
                sql:
                  file: orders.sql
                  mode: update
                response:
                  json:
                    status: 200
                """);
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1025".equals(finding.code())
                && finding.message().contains("query recipes"));
    }
}
