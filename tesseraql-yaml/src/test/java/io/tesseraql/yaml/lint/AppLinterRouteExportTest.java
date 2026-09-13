package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A route's {@code export:} block chooses a workbook mode by what it declares
 * (docs/export-pipeline.md, decision 4), so a declaration that cannot mean what it says produced
 * a different document in silence: a mistyped template path fell through to a plain grid, losing
 * the layout, the styles and the memory profile at once. Job export steps have been checked all
 * along; routes had only the PDF-specific check.
 */
class AppLinterRouteExportTest {

    private Path app(Path dir, String exportBody) throws Exception {
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
                security:
                  auth: public
                export:
                %s
                """.formatted(exportBody));
        return dir;
    }

    @Test
    void aTemplateThatIsNotThereIsAnErrorRatherThanAPlainGrid(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  template: styled.xlsx
                  startCell: B5
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1006");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("missing template", "styled.xlsx");
        });
    }

    @Test
    void aTemplateThatIsThereIsClean(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                  format: excel
                  template: styled.xlsx
                  startCell: B5
                  sql:
                    file: dump.sql
                    mode: query-export
                """);
        Files.write(app.resolve("web/items/styled.xlsx"), new byte[]{1});

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> "TQL-YAML-1006".equals(finding.code())
                        || "TQL-YAML-1005".equals(finding.code())
                        || "TQL-YAML-1041".equals(finding.code()));
    }

    @Test
    void placementWithoutATemplateNamesAModeThatDoesNotExist(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  startCell: B5
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("startCell:", "template:");
        });
    }

    @Test
    void aPlainGridIsStillClean(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, """
                  format: excel
                  sql:
                    file: dump.sql
                    mode: query-export
                """)))
                .noneMatch(finding -> "TQL-YAML-1006".equals(finding.code())
                        || "TQL-YAML-1005".equals(finding.code())
                        || "TQL-YAML-1041".equals(finding.code()));
    }

    /** The helper's own {@code method:}/{@code path:} keys draw unknown-key warnings, so "clean" is keyed on the message. */
    private static boolean namesTheMark(LintFinding finding) {
        return finding.message().contains("bom");
    }

    @Test
    void aByteOrderMarkOnAWorkbookRouteIsAnInapplicableOption(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("bom:", "excel");
        });
    }

    @Test
    void aByteOrderMarkOnAPdfRouteIsAnInapplicableOption(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: pdf
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("bom:", "pdf");
        });
    }

    /** Presence is what is refused, as with {@code sheet:} on a pdf: a declined mark is a leftover. */
    @Test
    void aDeclinedByteOrderMarkOnAWorkbookIsStillAnInapplicableOption(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  bom: false
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("bom:");
        });
    }

    @Test
    void aByteOrderMarkOnACsvRouteIsAKnownAndCleanKey(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: csv
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        // Neither inapplicable (1005) nor unknown (1043): nothing names the key.
        assertThat(findings).noneMatch(AppLinterRouteExportTest::namesTheMark);
    }

    /** An unset {@code format:} is a route's csv default, so the mark applies. */
    @Test
    void aByteOrderMarkWithoutAFormatIsTheCsvDefaultAndClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  filename: items.csv
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).noneMatch(AppLinterRouteExportTest::namesTheMark);
    }

    /** A format the framework does not ship belongs to a module codec; the linter does not judge it. */
    @Test
    void aByteOrderMarkOnAModuleFormatIsNotJudged(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: parquet
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1005".equals(finding.code())
                && namesTheMark(finding));
    }

    // ---- the values a declaration carries (docs/export-declarations.md, PR 5a) ----

    /** A file-export route with its own header keys (input:, security:) and export body. */
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

    private static final String PUBLIC = "security:\n  auth: public";

    /** The refusal that names the route, the key and the value — never a bare code match. */
    private static java.util.function.Predicate<LintFinding> refuses(String key, String value) {
        return finding -> "TQL-YAML-1063".equals(finding.code()) && finding.isError()
                && finding.message().contains("route 'items.dump'")
                && finding.message().contains(key)
                && finding.message().contains("'" + value + "'");
    }

    @Test
    void aMistypedZoneIsRefusedWhereItIsWritten(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC, """
                  format: excel
                  bom: true
                  timezone: Asia/Tokio
                """));

        assertThat(findings).anyMatch(refuses("export.timezone", "Asia/Tokio"));
        // The positive control on the same block: the linter did read this export.
        assertThat(findings).anyMatch(finding -> "TQL-YAML-1005".equals(finding.code())
                && finding.message().contains("bom:"));
    }

    @Test
    void aValidZoneAndLocaleDrawNoRefusal(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  timezone: Asia/Tokyo
                  locale: ja-JP
                  columns:
                    - { name: id, type: number, format: '#,##0' }
                """));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1063".equals(finding.code())
                || "TQL-YAML-1005".equals(finding.code()));
    }

    @Test
    void aMistypedLocaleIsRefusedWhereItIsWritten(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  locale: ja_JP
                """));

        assertThat(findings).anyMatch(refuses("export.locale", "ja_JP"));
        assertThat(findings).filteredOn(refuses("export.locale", "ja_JP")).first()
                .extracting(LintFinding::message).asString()
                .contains("not a language tag the JDK can format (expected e.g. en, ja-JP)");
    }

    @Test
    void aColumnPatternAndAnUnknownColumnTypeAreRefused(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  columns:
                    - { name: amount, type: number, format: '#,##0.00.00' }
                    - { name: when, type: timestamp }
                """));

        assertThat(findings).anyMatch(refuses("export.columns[amount].format", "#,##0.00.00"));
        // An unknown type renders as an untyped column today (the zone never reaches it): a
        // lint error the runtime warns about and serves through, not a refusal.
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("route 'items.dump'",
                    "export.columns[when].type", "'timestamp'", "not a column type the export"
                            + " renders");
        });
    }

    @Test
    void anExcelCellFormatIsNotJudgedByTheJdkParsers(@TempDir Path dir) throws Exception {
        // A workbook writes format: verbatim as the cell's number format (Excel's vocabulary);
        // the same list on csv goes through DateTimeFormatter and is refused.
        String columns = """
                  columns:
                    - { name: created, type: date, format: d-mmm-yy }
                    - { name: amount, type: number, format: '0.0,,"M"' }
                """;
        assertThat(new AppLinter().lint(route(dir, PUBLIC, "  format: excel\n" + columns)))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
        assertThat(new AppLinter().lint(route(dir.resolve("csv"), PUBLIC,
                "  format: csv\n" + columns)))
                .anyMatch(refuses("export.columns[created].format", "d-mmm-yy"));
    }

    @Test
    void aMixedCaseFormatNameIsRefusedAndAModuleFormatIsNot(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir, PUBLIC, "  format: Excel\n")))
                .anyMatch(refuses("format", "Excel"));
        assertThat(new AppLinter().lint(route(dir, PUBLIC, "  format: parquet\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
    }

    @Test
    void aCellReferencePastTheWorkbookIsRefused(@TempDir Path dir) throws Exception {
        Path app = route(dir, PUBLIC, """
                  format: excel
                  template: styled.xlsx
                  startCell: ZZZZ1
                """);
        Files.write(app.resolve("web/items/styled.xlsx"), new byte[]{1});

        assertThat(new AppLinter().lint(app)).anyMatch(refuses("export.startCell", "ZZZZ1"));
    }

    @Test
    void aSourceNamingNoDeclaredInputIsRefused(@TempDir Path dir) throws Exception {
        assertThat(
                new AppLinter().lint(route(dir, PUBLIC, "  format: csv\n  timezone: query.tz\n")))
                .anyMatch(refuses("export.timezone", "query.tz"));
        assertThat(new AppLinter().lint(route(dir, PUBLIC + "\ninput:\n  tz:\n    type: string",
                "  format: csv\n  timezone: query.tz\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
    }

    @Test
    void aPrefixOutsideTheSourceGrammarIsRefused(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(route(dir, PUBLIC, "  format: csv\n  timezone: header.tz\n"));

        assertThat(findings).filteredOn(refuses("export.timezone", "header.tz")).first()
                .extracting(LintFinding::message).asString()
                .contains("a request source starts with query., params., body., principal.");
    }

    @Test
    void aPrincipalSourceOnAPublicRouteIsRefused(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir, PUBLIC,
                "  format: csv\n  locale: principal.claim.locale\n")))
                .anyMatch(refuses("export.locale", "principal.claim.locale"));
        assertThat(
                new AppLinter().lint(route(dir, "security:\n  auth: bearer\n  policy: items.read",
                        "  format: csv\n  locale: principal.claim.locale\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
    }

    @Test
    void aLocaleOnAWorkbookIsAnInapplicableOption(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(route(dir, PUBLIC, "  format: excel\n  locale: ja-JP\n"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("route 'items.dump'", "export.locale",
                    "drives nothing in a workbook");
        });
    }

    @Test
    void reportModeKeysAreAWarning(@TempDir Path dir) throws Exception {
        Path app = route(dir, PUBLIC, """
                  format: excel
                  template: report.xlsx
                  timezone: Asia/Tokyo
                """);
        Files.write(app.resolve("web/items/report.xlsx"), new byte[]{1});

        assertThat(new AppLinter().lint(app)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("jxls report", "timezone:");
        });
    }

    @Test
    void anUntypedColumnListUnderAZoneIsAWarning(@TempDir Path dir) throws Exception {
        List<LintFinding> untyped = new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  timezone: Asia/Tokyo
                  columns: [id, name]
                """));

        assertThat(untyped).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("none of the declared columns",
                    "cannot see derived columns");
        });
        assertThat(new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  timezone: Asia/Tokyo
                  columns:
                    - id
                    - { name: created, type: datetime }
                """))).noneMatch(finding -> "TQL-YAML-1005".equals(finding.code()));
    }

    @Test
    void aFileExportWithoutAnExportBlockIsIncomplete(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir, PUBLIC, null))).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("route 'items.dump'", "export:");
        });
    }

    @Test
    void aFollowUpWithoutItsStatementIsIncomplete(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  after:
                    timing: extract
                """))).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("route 'items.dump'", "after.sql");
        });
    }

    // ---- the synthesis' arms (docs/export-declarations.md, the attacks' RUN findings) ----

    @Test
    void aBodySourceIsRefusedOnlyWhereTheBodyIsLimitedToDeclaredInputs(@TempDir Path dir)
            throws Exception {
        // RequestBinder publishes the raw body under body.; inputPolicy.unknownFields: ignore
        // keeps an undeclared field, so body.tz resolves today and must not be refused there.
        assertThat(new AppLinter().lint(route(dir, PUBLIC
                + "\ninputPolicy:\n  unknownFields: ignore",
                "  format: csv\n  timezone: body.tz\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
        assertThat(new AppLinter().lint(route(dir.resolve("strict"), PUBLIC,
                "  format: csv\n  timezone: body.tz\n")))
                .anyMatch(refuses("export.timezone", "body.tz"));
    }

    @Test
    void aFollowUpOnAQueryExportIsLeftToTheCompilersOwnRefusal(@TempDir Path dir)
            throws Exception {
        // TQL-ROUTE-3101 is the boot's answer to after: on a query-export; a lint 1041 "add the
        // statement" would send the author into it.
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
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
                  after:
                    timing: extract
                sources:
                  main:
                    sql:
                      file: dump.sql
                      mode: query-export
                """);

        assertThat(new AppLinter().lint(dir))
                .noneMatch(finding -> "TQL-YAML-1041".equals(finding.code()));
    }

    @Test
    void aTemplateOnCsvIsAnInapplicableOptionNotAMissingFile(@TempDir Path dir)
            throws Exception {
        // csv reads no template: the key is inert (1005), its file is not looked for (1006).
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC,
                "  format: csv\n  template: styled.xlsx\n"));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1006".equals(finding.code()));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("route 'items.dump'", "export.template",
                    "'styled.xlsx'", "reads no template");
        });
    }

    @Test
    void aMistypedZoneOnAReportTemplateIsTheAdvisoryNotARefusal(@TempDir Path dir)
            throws Exception {
        // A jxls report never calls ColumnValues.zone (served today): the warning that the key
        // reaches no cell is the finding; the value is judged the day the mode reads it.
        Path app = route(dir, PUBLIC, """
                  format: excel
                  template: report.xlsx
                  timezone: Asia/Tokio
                """);
        Files.write(app.resolve("web/items/report.xlsx"), new byte[]{1});

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("jxls report", "timezone:");
        });
    }

    @Test
    void aCldrAliasedLocaleTheJdkRendersIsClean(@TempDir Path dir) throws Exception {
        // tl (Filipino) is absent from Locale.getAvailableLocales() and rendered with its own
        // data by Locale.forLanguageTag — a language-set rule alone refused a working app.
        assertThat(new AppLinter().lint(route(dir, PUBLIC, "  format: csv\n  locale: tl\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
        assertThat(new AppLinter().lint(route(dir.resolve("word"), PUBLIC,
                "  format: csv\n  locale: japanese\n")))
                .anyMatch(refuses("export.locale", "japanese"));
    }

    @Test
    void aLocaleWithAStraySpaceIsRefusedAsTheRuntimeWouldRenderRoot(@TempDir Path dir)
            throws Exception {
        assertThat(new AppLinter().lint(route(dir, PUBLIC, "  format: csv\n  locale: ' ja-JP'\n")))
                .anyMatch(refuses("export.locale", " ja-JP"));
        assertThat(new AppLinter().lint(route(dir.resolve("z"), PUBLIC,
                "  format: csv\n  timezone: ' Asia/Tokyo'\n")))
                .anyMatch(refuses("export.timezone", " Asia/Tokyo"));
    }

    @Test
    void theFindingPointsAtTheKeyInsideTheExportBlock(@TempDir Path dir) throws Exception {
        // format: and type: are the commonest words in a route file; the line must be the
        // export block's own key, not the input's.
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC + """

                input:
                  when:
                    type: string
                    format: date""", """
                  format: csv
                  timezone: Asia/Tokio
                  columns:
                    - { name: amount, type: number, format: '#,##0.00.00' }
                """));

        LintFinding zone = findings.stream().filter(refuses("export.timezone", "Asia/Tokio"))
                .findFirst().orElseThrow();
        LintFinding pattern = findings.stream()
                .filter(refuses("export.columns[amount].format", "#,##0.00.00")).findFirst()
                .orElseThrow();
        List<String> lines = Files.readAllLines(dir.resolve("web/items/get.yml"));
        assertThat(lines.get(zone.line() - 1)).contains("timezone: Asia/Tokio");
        assertThat(lines.get(pattern.line() - 1)).contains("#,##0.00.00");
    }

    @Test
    void noRouteFindingCarriesAnUnboundedValue(@TempDir Path dir) throws Exception {
        String huge = "A".repeat(2000) + "B".repeat(3000);
        List<LintFinding> findings = new AppLinter().lint(route(dir, PUBLIC, """
                  format: csv
                  timezone: query.%s
                  columns:
                    - { name: %s, type: number, format: '#,##0.00.00%s' }
                """.formatted(huge, huge, huge)));

        assertThat(findings).filteredOn(finding -> "TQL-YAML-1063".equals(finding.code()))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(finding -> assertThat(finding.message()).hasSizeLessThan(500));
    }
}
