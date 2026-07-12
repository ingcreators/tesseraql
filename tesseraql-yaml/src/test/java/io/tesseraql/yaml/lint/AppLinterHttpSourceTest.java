package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * http: source lints (docs/connectors.md, "HTTP sources"): query recipes only, no shadowing
 * of SQL result keys, and the same egress checks as a job's http-call step.
 */
class AppLinterHttpSourceTest {

    private static void writeApp(Path dir, String recipe, String allowedHosts, String extra)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  http:
                    outbound:
                      allowedHosts:
                        - %s
                      credentials:
                        fx-api:
                          type: bearer
                          token: dummy
                """.formatted(allowedHosts));
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: %s
                sql:
                  file: orders.sql
                http:
                  rates:
                    url: https://fx.example.com/v1/rates
                    credential: fx-api
                %s
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                      fx: rates.body
                """.formatted(recipe, extra));
    }

    @Test
    void anAllowListedSourceOnAQueryRouteLintsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "fx.example.com", "");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void aDeniedHostIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "other.example.com", "");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-SEC-4070".equals(finding.code()));
    }

    @Test
    void httpSourcesAreAQueryRecipeKey(@TempDir Path dir) throws Exception {
        writeApp(dir, "command-json", "fx.example.com", "");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1022".equals(finding.code())
                && finding.message().contains("query recipes"));
    }

    @Test
    void aSourceNameMustNotShadowANamedQuery(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "fx.example.com", "");
        Files.writeString(dir.resolve("web/orders/count.sql"), "select 1 as n\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                sql:
                  file: orders.sql
                queries:
                  rates:
                    file: count.sql
                http:
                  rates:
                    url: https://fx.example.com/v1/rates
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """);
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1022".equals(finding.code())
                && finding.message().contains("shadows"));
    }
}
