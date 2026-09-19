package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export renders no catalog name (docs/lookups.md, decision 12 as built): both export
 * chains publish the declared source names only, so a {@code csv} or {@code pdf} export in an
 * app whose catalogs carry per-language names is not asked for a locale a name would answer
 * in. The rule that asked ({@code TQL-FIELD-4622}, 0.14.0 to 0.17.0) drew an ERROR — a failed
 * default lint gate — for a capability no export had; an export's {@code locale:} formats its
 * numbers and dates, and the export-declaration rules judge it.
 */
class AppLinterCatalogLocaleTest {

    private Path app(Path dir, String catalogBody, String exportBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("catalogs"));
        Files.writeString(dir.resolve("catalogs/codes.yml"), """
                version: tesseraql/v1
                catalogs:
                  取引区分:
                    table: 区分マスタ
                    key: 区分コード
                    label: 区分名称
                %s
                """.formatted(catalogBody));
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/dump.sql"), "select id from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: query-export
                method: GET
                path: /api/items/dump
                security:
                  auth: public
                export:
                  format: csv
                  filename: items.csv
                %s
                sources:
                  main:
                    sql:
                      file: dump.sql
                      mode: query-export
                """.formatted(exportBody));
        return dir;
    }

    @Test
    void anExportWithNoLocaleLintsCleanWhenNamesArePerLanguage(@TempDir Path dir)
            throws Exception {
        // The multilingual app with an un-localed csv export: the shape the retired rule
        // refused. No error of any code — the gate is errors-only, so a finding of another
        // severity here would not fail it, but none is expected either.
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    language: 言語コード", "  columns:\n    - name: id"));
        assertThat(findings).filteredOn(LintFinding::isError).isEmpty();
        assertThat(findings).extracting(LintFinding::code).doesNotContain("TQL-FIELD-4622");
    }

    @Test
    void aDeclaredLocaleIsJudgedAsAFormattingDeclarationOnly(@TempDir Path dir)
            throws Exception {
        // locale: on untyped csv columns reaches nothing (docs/export-declarations.md): the
        // export-declaration advisory says so, and no catalog rule has an opinion.
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    language: 言語コード",
                        "  locale: en\n  columns:\n    - name: id"));
        assertThat(findings).extracting(LintFinding::code).contains("TQL-YAML-1005")
                .doesNotContain("TQL-FIELD-4622");
        assertThat(findings).filteredOn(LintFinding::isError).isEmpty();
    }

    @Test
    void aWorkbookExportIsJudgedByTheExportDeclarationRulesAlone(@TempDir Path dir)
            throws Exception {
        // docs/export-declarations.md decision 6 refuses locale: on excel (TQL-YAML-1005); the
        // case-folded spelling is a format the codec set does not name (TQL-YAML-1063).
        Path app = app(dir, "    language: 言語コード", "  columns:\n    - name: id");
        Path route = app.resolve("web/items/get.yml");
        Files.writeString(route, Files.readString(route).replace("format: csv", "format: excel"));
        assertThat(new AppLinter().lint(app)).filteredOn(LintFinding::isError).isEmpty();

        Files.writeString(route, Files.readString(route).replace("format: excel", "format: Excel"));
        assertThat(new AppLinter().lint(app)).extracting(LintFinding::code)
                .contains("TQL-YAML-1063").doesNotContain("TQL-FIELD-4622");

        Files.writeString(route, Files.readString(route).replace("format: Excel",
                "format: excel\n  locale: en"));
        List<LintFinding> declared = new AppLinter().lint(app);
        assertThat(declared).extracting(LintFinding::code).contains("TQL-YAML-1005")
                .doesNotContain("TQL-FIELD-4622", "TQL-YAML-1063");
    }
}
