package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export answers in its own locale (docs/lookups.md, decision 12). A request negotiates one;
 * an export has none to negotiate — it is generated for a file, often on a schedule, and read by
 * someone who never made the request. So when the app's catalogs carry per-language names, an
 * export that declares no locale is refused at build rather than answering in whichever language
 * the server happened to start in.
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
                sql:
                  file: dump.sql
                  mode: query-export
                export:
                  format: csv
                  filename: items.csv
                %s
                """.formatted(exportBody));
        return dir;
    }

    @Test
    void anExportWithNoLocaleIsRefusedWhenNamesArePerLanguage(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    language: 言語コード", "  columns:\n    - name: id"));
        assertThat(findings).extracting(LintFinding::code).contains("TQL-FIELD-4622");
    }

    @Test
    void declaringTheExportsLocaleSatisfiesIt(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    language: 言語コード",
                        "  locale: en\n  columns:\n    - name: id"));
        assertThat(findings).extracting(LintFinding::code).doesNotContain("TQL-FIELD-4622");
    }

    @Test
    void aSingleLanguageAppIsNotAskedToDeclareOne(@TempDir Path dir) throws Exception {
        // One answer whatever the locale: demanding a declaration here would be ceremony.
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    order: 表示順", "  columns:\n    - name: id"));
        assertThat(findings).extracting(LintFinding::code).doesNotContain("TQL-FIELD-4622");
    }
}
