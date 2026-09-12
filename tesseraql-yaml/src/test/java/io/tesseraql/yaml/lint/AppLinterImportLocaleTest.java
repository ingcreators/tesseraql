package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The import arms are where a mistyped locale costs data: {@code import.locale: de_DE} parsed
 * {@code 1234,50} as {@code 123450.00} at COMPLETED (docs/export-declarations.md). The same
 * predicate judges a route's {@code import.locale} and a poll job's.
 */
class AppLinterImportLocaleTest {

    private static Path importRoute(Path dir, String importBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/items/import"));
        Files.writeString(dir.resolve("web/items/import/upsert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        Files.writeString(dir.resolve("web/items/import/post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                method: POST
                path: /api/items/import
                security:
                  auth: bearer
                  policy: items.write
                import:
                %s
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(importBody));
        return dir;
    }

    private static Path pollJob(Path dir, String importBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  connectors:
                    poll:
                      allowedPaths:
                        - inbound
                """);
        Files.createDirectories(dir.resolve("batch/intake"));
        Files.writeString(dir.resolve("batch/intake/upsert.sql"), "insert into t values (1)\n");
        Files.writeString(dir.resolve("batch/intake/job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: inbound
                    consumeOnce: true
                import:
                %s
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(importBody));
        return dir;
    }

    @Test
    void aMistypedImportLocaleOnARouteIsRefusedNamingTheRoute(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(importRoute(dir, """
                  format: csv
                  locale: de_DE
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("route 'items.import'", "import.locale",
                    "'de_DE'");
        });
    }

    @Test
    void aValidImportLocaleOnARouteIsClean(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(importRoute(dir, "  format: csv\n  locale: de-DE\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
    }

    @Test
    void aMistypedImportLocaleOnAPollJobIsRefusedNamingTheJob(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(pollJob(dir, """
                  format: csv
                  locale: de_DE
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
            assertThat(finding.message()).contains("job 'orders.intake'", "import.locale",
                    "'de_DE'");
        });
    }

    @Test
    void aSourceExpressionOnAPollJobImportIsRefused(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(pollJob(dir, "  format: csv\n  locale: request.locale\n")))
                .anySatisfy(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
                    assertThat(finding.message()).contains("job 'orders.intake'",
                            "a job has no request");
                });
    }

    @Test
    void aPrincipalSourceOnAPollJobImportIsRefusedBecauseAJobHasNoRequest(@TempDir Path dir)
            throws Exception {
        // PollSources hands the string verbatim to Locale.forLanguageTag: 'principal.claim
        // .locale' is und there, and 1234,50 imports as 123450.00 at COMPLETED.
        assertThat(new AppLinter().lint(pollJob(dir,
                "  format: csv\n  locale: principal.claim.locale\n"))).anySatisfy(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
                    assertThat(finding.message()).contains("job 'orders.intake'",
                            "'principal.claim.locale'", "a job has no request");
                });
    }

    @Test
    void anImportColumnPatternAndTypeAreRefusedAsEveryRowWouldBe(@TempDir Path dir)
            throws Exception {
        assertThat(new AppLinter().lint(importRoute(dir, """
                  format: csv
                  columns:
                    - { name: fee, type: number, format: '#,##0.00.00' }
                    - { name: qty, type: integer }
                """))).filteredOn(finding -> "TQL-YAML-1063".equals(finding.code()))
                .extracting(LintFinding::message)
                .anySatisfy(message -> assertThat(message).contains("import.columns[fee].format"))
                .anySatisfy(message -> assertThat(message).contains("import.columns[qty].type",
                        "'integer'"));
    }
}
