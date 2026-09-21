package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bench scenarios are lint-checked like a suite (docs/deployment-maturity.md decision 8): an
 * unknown route, a param the route does not declare, and a write without {@code writes: allowed}
 * are errors before a request is sent. The variant with no bench arm is red on every case.
 */
class AppLinterBenchTest {

    private static Path app(Path dir, String scenario) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path items = dir.resolve("web/api/items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: items.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(items.resolve("items.sql"), "select 1 as id\n");
        Files.writeString(items.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: items.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        if (scenario != null) {
            Files.createDirectories(dir.resolve("bench"));
            Files.writeString(dir.resolve("bench/browse.yml"), scenario);
        }
        return dir;
    }

    private static List<String> benchCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code)
                .filter(code -> code.compareTo("TQL-YAML-1413") >= 0
                        && code.compareTo("TQL-YAML-1416") <= 0)
                .toList();
    }

    @Test
    void aValidScenarioRaisesNoBenchFinding(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                kind: bench
                concurrency: 4
                duration: 5s
                requests:
                  - route: items.list
                    params: { q: "a" }
                    weight: 3
                """));

        assertThat(benchCodes(findings)).isEmpty();
    }

    @Test
    void anUnknownRouteIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                kind: bench
                requests:
                  - route: items.serach
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1414");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("bench/browse.yml");
            assertThat(finding.message()).contains("items.serach");
        });
    }

    @Test
    void aParamTheRouteDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                kind: bench
                requests:
                  - route: items.list
                    params: { q: "a", page: 2 }
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1415");
            assertThat(finding.message()).contains("'page'").contains("items.list");
        });
        assertThat(benchCodes(findings)).as("q is declared, page is not").hasSize(1);
    }

    @Test
    void aWriteWithoutTheDeclarationIsAnErrorAndWithItIsNot(@TempDir Path dir) throws Exception {
        List<LintFinding> refused = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                kind: bench
                requests:
                  - route: items.create
                    body: { name: "widget" }
                """));
        assertThat(refused).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1416");
            assertThat(finding.message()).contains("items.create").contains("POST")
                    .contains("writes: allowed");
        });

        Files.writeString(dir.resolve("bench/browse.yml"), """
                version: tesseraql/v1
                kind: bench
                writes: allowed
                requests:
                  - route: items.create
                    body: { name: "widget" }
                """);
        assertThat(benchCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aScenarioWithoutRequestsOrKindIsMalformed(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                kind: bench
                concurrency: 4
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1413");
            assertThat(finding.message()).contains("no requests");
        });
    }

    @Test
    void anUnknownKeyIsReportedLikeAnyDocuments(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                kind: bench
                concurency: 4
                requests:
                  - route: items.list
                """));

        assertThat(findings).anySatisfy(finding -> assertThat(finding.message())
                .contains("concurency"));
    }
}
