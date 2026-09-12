package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The linter's report of the export-declaration predicate on the shapes the other lint classes
 * do not walk (docs/export-declarations.md decisions 20-26): the site rules — a route with no
 * {@code security:} block binds no principal, a {@code query.} source on an import route has no
 * request to read — the import arm's column type and format name, a follow-up with a SQL arm but
 * no file, and a locale with a leading space, which the runtime would render as the root locale.
 * Each case was first a built variant every other guard was green on.
 */
class AppLinterExportDeclarationsTest {

    private static Path route(Path dir, String headerKeys, String exportBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/dump.sql"), "select id from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: file-export
                method: GET
                path: /api/items/dump
                %s
                sources:
                  main:
                    sql:
                      file: dump.sql
                """.formatted(headerKeys) + (exportBody == null ? "" : "export:\n" + exportBody));
        return dir;
    }

    private static Path importRoute(Path dir, String headerKeys, String importBody)
            throws Exception {
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
                %s
                import:
                %s
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(headerKeys, importBody));
        return dir;
    }

    private static java.util.function.Predicate<LintFinding> refuses(String routeId, String key,
            String value) {
        return finding -> "TQL-YAML-1063".equals(finding.code()) && finding.isError()
                && finding.message().contains("route '" + routeId + "'")
                && finding.message().contains(key)
                && finding.message().contains("'" + value + "'");
    }

    // Red on a site that classifies an ABSENT security block as authenticated (principal.*
    // would pass on a route nothing authenticates).
    @Test
    void anAbsentSecurityBlockIsPublicForThePrincipalArm(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(route(dir, "", "  format: csv\n  locale: principal.claim.locale\n"));

        assertThat(findings)
                .anyMatch(refuses("items.dump", "export.locale", "principal.claim.locale"));
    }

    // Red on an import arm that never judges columns(): an unknown import column type would
    // lint clean.
    @Test
    void anImportColumnTypeIsJudgedByTheSameRule(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(importRoute(dir,
                "security:\n  auth: bearer\n  policy: items.write", """
                          format: csv
                          columns:
                            - { name: qty, type: integer }
                        """));

        assertThat(findings).anyMatch(refuses("items.import", "import.columns[qty].type",
                "integer"));
    }

    // Red on an import arm that never judges the format name: import.format: Csv would lint
    // clean.
    @Test
    void aMixedCaseImportFormatNameIsRefused(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(importRoute(dir,
                "security:\n  auth: bearer\n  policy: items.write", "  format: Csv\n"));

        assertThat(findings).anyMatch(refuses("items.import", "format", "Csv"));
    }

    // Red on an after: arm that checks only sql == null: after.sql: {mode: update} without a
    // file would pass lint and fail the compile with a null pointer.
    @Test
    void aFollowUpWithASqlArmButNoFileIsIncomplete(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir,
                "security:\n  auth: public", """
                          format: csv
                          after:
                            timing: extract
                            sql:
                              mode: update
                        """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("route 'items.dump'", "after.sql");
        });
    }

    // Red on a locale rule that strips: ' ja-JP' would pass lint and boot while
    // Locale.forLanguageTag(" ja-JP") renders the root locale (und) at run time.
    // The design's zone list carries 'Asia/Tokyo '; its locale lists carry no whitespace case.
    @Test
    void aLocaleWithALeadingSpaceIsRefusedAsTheRuntimeWouldRenderRoot(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(route(dir, "security:\n  auth: public",
                        "  format: csv\n  locale: ' ja-JP'\n"));

        assertThat(findings).anyMatch(refuses("items.dump", "export.locale", " ja-JP"));
    }

    // A file-import route wires no request binder, so query./params./body. never resolve
    // there: the predicate refuses them even when input: declares the name.
    @Test
    void aQuerySourceOnAnImportRouteIsRefusedBecauseTheImportBindsNoRequest(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(importRoute(dir, """
                security:
                  auth: bearer
                  policy: items.write
                input:
                  lang:
                    type: string
                """, "  format: csv\n  locale: query.lang\n"));

        assertThat(findings).anyMatch(refuses("items.import", "import.locale", "query.lang"));
    }
}
