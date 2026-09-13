package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Site;
import io.tesseraql.yaml.app.ExportDeclarations.Surface;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.ColumnSpec;
import io.tesseraql.yaml.model.ExportSpec;
import io.tesseraql.yaml.model.ImportSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one predicate both the linter and the boot refusal judge an export declaration by
 * (docs/export-declarations.md): a value is refused exactly when the runtime would fail on
 * it where it reads it, a key the format never reads is inert, and the message names the app,
 * the route, the key and the bounded value.
 */
class ExportDeclarationsTest {

    private static final Site ROUTE = new Site("t", "route 'items.dump'", Surface.QUERY_EXPORT,
            Set.of("tz"), true, true);

    private static final Site FILE_EXPORT = new Site("t", "route 'items.dump'",
            Surface.FILE_EXPORT, Set.of("tz"), true, true);

    private static ExportSpec export(String format, String locale, String timezone) {
        return new ExportSpec(format, null, null, null, null, List.of(), locale, timezone, null,
                null, null, null, null, null);
    }

    private static ExportSpec columns(String format, ColumnSpec... columns) {
        return new ExportSpec(format, null, null, null, null, List.of(columns), null, null, null,
                null, null, null, null, null);
    }

    private static List<Violation> refusals(Site site, ExportSpec spec) {
        return ExportDeclarations.violations(site, spec, null).stream()
                .filter(violation -> violation.kind() == Kind.INVALID).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Tokio", "asia/tokyo", "JST", "EST", "Tokyo", "utc",
            "Asia/Tokyo ", " Asia/Tokyo"})
    void aZoneTheCodecWouldThrowOnIsRefusedAsDeclared(String zone) {
        // ZoneId.of as the codec calls it: region ids are case-sensitive, the short ids (JST,
        // EST) ZoneId.SHORT_IDS would map are not zones ColumnValues.zone accepts, and a
        // stray space is not trimmed — the codec would throw on it.
        List<Violation> refused = refusals(ROUTE, export("csv", null, zone));

        assertThat(refused).singleElement().satisfies(violation -> {
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1063");
            assertThat(violation.message()).contains("app 't'", "route 'items.dump'",
                    "export.timezone", "'" + zone + "'");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Tokyo", "UTC", "Z", "+09:00", "Etc/GMT-9", "GMT+9", "", "  "})
    void aZoneTheCodecAcceptsPasses(String zone) {
        assertThat(refusals(ROUTE, export("csv", null, zone))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja_JP", "de_DE", "japanese", "xx-YY", "und", "'; drop", "no-NO-NY",
            "x-private", "ain", "tlh", "i-klingon", " ja-JP", "ja-JP "})
    void aLocaleTheJdkCannotFormatIsRefused(String locale) {
        // Decision 7: the strict parse plus the JDK's own formatting data; the message says
        // "format", never "BCP-47" — ain and x-private are well-formed and still render as
        // ROOT. A stray space fails the parse, as Locale.forLanguageTag(" ja-JP") is und.
        List<Violation> refused = refusals(ROUTE, export("csv", locale, null));

        assertThat(refused).singleElement().satisfies(violation -> {
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1063");
            assertThat(violation.message()).contains("export.locale", "'" + locale + "'",
                    "not a language tag the JDK can format (expected e.g. en, ja-JP)");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja-JP", "en", "de-CH", "ja-JP-u-nu-fullwide", "ja-JP-u-ca-japanese",
            "en-US-x-foo", "zh-Hant-TW", "JA-jp", "nn-NO", "en-GB-oed", "", " ",
            // CLDR aliases: not in Locale.getAvailableLocales(), rendered with their own data
            // by the runtime's Locale.forLanguageTag (Filipino, Serbo-Croatian, Moldavian,
            // Montenegrin) — a language-set rule alone refuses a working declaration.
            "tl", "sh", "mo", "cnr"})
    void aLocaleTheJdkFormatsPasses(String locale) {
        assertThat(refusals(ROUTE, export("csv", locale, null))).isEmpty();
    }

    @Test
    void theLocaleRuleIsNotTheI18nNormalizer() {
        // I18nSettings.normalize folds ja_JP to und; the predicate must refuse it instead.
        assertThat(ExportDeclarations.isFormattableLocale("ja_JP")).isFalse();
        assertThat(ExportDeclarations.isFormattableLocale("und")).isFalse();
        assertThat(ExportDeclarations.isFormattableLocale("ja-JP")).isTrue();
        assertThat(ExportDeclarations.isFormattableLocale("tl")).isTrue();
    }

    @Test
    void aFormatNameInMixedCaseIsRefusedAndAnUnknownNameIsNot() {
        assertThat(refusals(ROUTE, export("Excel", null, null))).singleElement()
                .extracting(Violation::message).asString()
                .contains("'Excel'", "format names are lower-case (excel)");
        // A module codec's name is not the predicate's to judge (F82 slice 2).
        assertThat(ExportDeclarations.violations(ROUTE, export("parquet", null, null), null))
                .isEmpty();
    }

    @Test
    void aColumnPatternIsJudgedByItsTypeAndWeaklyWithoutOneWhereTheParserRuns() {
        ExportSpec typed = columns("csv",
                // DecimalFormat takes letters as a literal affix, so 'yyyy' on a number column
                // is a VALID pattern (MEASUREMENT.md hazard 12) - the type arm cannot catch it.
                ColumnSpec.of("amount", null, null, "number", "yyyy"),
                ColumnSpec.of("when", null, null, "datetime", "#,##0.00"),
                ColumnSpec.of("ok", null, null, "number", "#,##0.00"),
                ColumnSpec.of("untyped-bad", null, null, null, "#,##0.00.00"),
                ColumnSpec.of("untyped-quote", null, null, null, "yyyy/MM/dd'"),
                // Without type: the runtime picks the parser per value, and DecimalFormat
                // accepts 'ss.ffff' - the untyped arm is documented as weaker.
                ColumnSpec.of("untyped-weak", null, null, null, "yyyy-MM-dd HH:mm:ss.ffff"));

        List<Violation> refused = refusals(ROUTE, typed);

        assertThat(refused).extracting(Violation::key).containsExactly(
                "export.columns[when].format", "export.columns[untyped-bad].format",
                "export.columns[untyped-quote].format");
        assertThat(refused.get(0).message()).contains("'#,##0.00'",
                "not a DateTimeFormatter pattern");
        assertThat(refused.get(1).message()).contains("neither a DecimalFormat nor a"
                + " DateTimeFormatter pattern");
        assertThat(ExportDeclarations.patternProblem("number", "#,##0.00.00"))
                .contains("not a DecimalFormat pattern");
        // pdf runs the same parsers (ColumnValues.format); a module codec's are unknown.
        assertThat(refusals(ROUTE, columns("pdf",
                ColumnSpec.of("amount", null, null, "number", "#,##0.00.00")))).hasSize(1);
        assertThat(refusals(ROUTE, columns("parquet",
                ColumnSpec.of("amount", null, null, "number", "#,##0.00.00")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"d-mmm-yy", "mmmm d, yyyy", "h:mm AM/PM", "0.0,,\"M\"", "0.00E+00",
            "mmm-yy", "#,##0.00;[Red]-#,##0.00"})
    void anExcelCellFormatIsTheWorkbooksToJudge(String cellFormat) {
        // A grid or placement workbook writes format: verbatim as the cell's own number
        // format (JxlsFileCodec.writeValue / columnStyles) — Excel's vocabulary, not Java's,
        // so the JDK parsers must not refuse a working declaration there.
        ExportSpec excel = columns("excel",
                ColumnSpec.of("created", null, null, "date", cellFormat),
                ColumnSpec.of("amount", null, null, "number", cellFormat));

        assertThat(ExportDeclarations.violations(ROUTE, excel, null)).isEmpty();
        // The csv twin of the same list goes through DateTimeFormatter/DecimalFormat, and
        // every Excel-native format above fails at least one of them.
        assertThat(refusals(ROUTE, columns("csv",
                ColumnSpec.of("created", null, null, "date", cellFormat),
                ColumnSpec.of("amount", null, null, "number", cellFormat)))).isNotEmpty();
    }

    @Test
    void anUnknownColumnTypeIsInertOnAnExportAndRefusedOnAnImport() {
        // ColumnValues.format renders an unknown type exactly as an untyped column (the zone
        // never reaches it) — served today, so a lint error and a boot warning; ColumnValues
        // .parse throws "Unknown column type" on every imported row — refused.
        List<Violation> export = ExportDeclarations.violations(ROUTE,
                columns("csv", ColumnSpec.of("held", null, null, "timestamp", null)), null);
        List<Violation> imported = ExportDeclarations.violations(ROUTE, new ImportSpec("csv",
                List.of(ColumnSpec.of("held", null, null, "timestamp", null)), null, null, null,
                null, null, null));

        assertThat(export).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Kind.INERT);
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1005");
            assertThat(violation.message()).contains("export.columns[held].type",
                    "'timestamp'", "not a column type the export renders");
        });
        assertThat(imported).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Kind.INVALID);
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1063");
            assertThat(violation.message()).contains("import.columns[held].type",
                    "'timestamp'", "not a column type the import can parse");
        });
    }

    @Test
    void aCellOrColumnReferenceOutsideAWorkbookIsRefusedOnAWorkbook() {
        ExportSpec spec = new ExportSpec("excel", null, "t.xlsx", null, "ZZZZ1", List.of(
                ColumnSpec.of("a", null, "D5", null, null),
                ColumnSpec.of("b", null, "0", null, null),
                ColumnSpec.of("c", null, "XFE", null, null),
                ColumnSpec.of("d", null, "XFD", null, null),
                // seven letters overflow parseColumn's int arithmetic into a negative index
                ColumnSpec.of("e", null, "ZZZZZZZ", null, null)),
                null, null, null, null, null, null, null, null);

        List<Violation> refused = refusals(ROUTE, spec);

        assertThat(refused).extracting(Violation::key).containsExactly(
                "export.columns[a].column", "export.columns[b].column",
                "export.columns[c].column", "export.columns[e].column", "export.startCell");
        assertThat(refused.get(4).message()).contains("'ZZZZ1'", "outside a workbook");
        assertThat(refusals(ROUTE, new ExportSpec("excel", null, "t.xlsx", null, "5B",
                List.of(), null, null, null, null, null, null, null, null)))
                .singleElement().extracting(Violation::message).asString()
                .contains("'5B'", "not a cell reference");
        assertThat(refusals(ROUTE, new ExportSpec("excel", null, "t.xlsx", null, "A1048577",
                List.of(), null, null, null, null, null, null, null, null)))
                .singleElement().extracting(Violation::message).asString()
                .contains("'A1048577'", "outside a workbook");
    }

    @Test
    void theWorkbookBoundIsNotJudgedWhereNoWorkbookReadsIt() {
        // toWriteSpec parses the grammar on every format (a raw throw today), but only a
        // workbook has a last column: csv serves ZZZZ1 and D5 is still not a cell.
        ExportSpec csv = new ExportSpec("csv", null, null, null, "ZZZZ1", List.of(
                ColumnSpec.of("c", null, "XFE", null, null),
                ColumnSpec.of("bad", null, "D5", null, null)),
                null, null, null, null, null, null, null, null);

        List<Violation> refused = refusals(ROUTE, csv);

        assertThat(refused).extracting(Violation::key)
                .containsExactly("export.columns[bad].column");
        assertThat(refusals(ROUTE, new ExportSpec("csv", null, null, null, "5B", List.of(),
                null, null, null, null, null, null, null, null))).hasSize(1);
    }

    @Test
    void aSourceExpressionIsClassifiedByTheBindersGrammar() {
        assertThat(ExportDeclarations.isSourceExpression("query.tz")).isTrue();
        assertThat(ExportDeclarations.isSourceExpression("principal.claim.zoneinfo")).isTrue();
        assertThat(ExportDeclarations.isSourceExpression("request.locale")).isTrue();
        assertThat(ExportDeclarations.isSourceExpression("Asia/Tokyo")).isFalse();
        assertThat(ExportDeclarations.isSourceExpression("header.tz")).isFalse();
        assertThat(ExportDeclarations.isSourceExpression("query.")).isFalse();
        assertThat(ExportDeclarations.isSourceExpression(null)).isFalse();
    }

    @Test
    void aSourceOnARouteIsJudgedForWhatItCanResolve() {
        assertThat(refusals(ROUTE, export("csv", null, "query.tz"))).isEmpty();
        assertThat(refusals(ROUTE, export("csv", "request.locale", null))).isEmpty();
        assertThat(refusals(ROUTE, export("csv", "principal.claim.locale", null))).isEmpty();
        assertThat(refusals(ROUTE, export("csv", null, "query.zone"))).singleElement()
                .extracting(Violation::message).asString()
                .contains("'query.zone'", "names no declared input", "input: zone");
        assertThat(refusals(ROUTE, export("csv", null, "query..tz"))).singleElement()
                .extracting(Violation::message).asString().contains("names no field");
        assertThat(refusals(ROUTE, export("csv", null, "header.tz"))).singleElement()
                .extracting(Violation::message).asString()
                .contains("'header.tz'", "not a time-zone id", "a request source starts with");
        assertThat(refusals(ROUTE, export("csv", null, "request.locale"))).singleElement()
                .extracting(Violation::message).asString().contains("names nothing a request");
        Site publicRoute = new Site("t", "route 'items.dump'", Surface.QUERY_EXPORT, Set.of(),
                false, true);
        assertThat(refusals(publicRoute, export("csv", null, "principal.claim.zoneinfo")))
                .singleElement().extracting(Violation::message).asString()
                .contains("the route is public");
    }

    @Test
    void aBodySourceIsHeldToTheDeclaredInputsOnlyWhereTheBodyIsDeclaredOnly() {
        // RequestBinder publishes the RAW body under body. (query./params. carry the declared
        // inputs only), and inputPolicy.unknownFields: ignore keeps an undeclared field — so
        // body.tz resolves there today and must not be refused as "names no declared input".
        Site ignoring = new Site("t", "route 'items.dump'", Surface.FILE_EXPORT, Set.of(), true,
                false);
        Site rejecting = new Site("t", "route 'items.dump'", Surface.FILE_EXPORT, Set.of(), true,
                true);

        assertThat(refusals(ignoring, export("csv", null, "body.tz"))).isEmpty();
        assertThat(refusals(ignoring, export("csv", null, "body.prefs.tz"))).isEmpty();
        assertThat(refusals(ignoring, export("csv", null, "body..tz"))).singleElement()
                .extracting(Violation::message).asString().contains("names no field");
        assertThat(refusals(ignoring, export("csv", null, "query.tz"))).singleElement()
                .extracting(Violation::message).asString().contains("names no declared input");
        assertThat(refusals(rejecting, export("csv", null, "body.tz"))).singleElement()
                .extracting(Violation::message).asString().contains("names no declared input");
    }

    @Test
    void aSourceOnAJobStepIsRefusedBecauseAJobHasNoRequest() {
        Site step = Site.step("t", "report.daily", "report");

        assertThat(refusals(step, export("csv", null, "query.tz"))).singleElement()
                .extracting(Violation::message).asString()
                .contains("job 'report.daily' step 'report'", "'query.tz'", "a job has no request");
        assertThat(refusals(step, export("csv", "principal.claim.locale", null))).hasSize(1);
        assertThat(refusals(step, export("csv", "ja-JP", "Asia/Tokyo"))).isEmpty();
    }

    @Test
    void aStepWithoutAFormatIsJudgedOnItsValuesOnly() {
        // The step's missing format: is the linter's own TQL-YAML-1041; the format-dependent
        // arms (an inert sheet: on csv, say) must not fire against a default the step has not.
        Site step = Site.step("t", "report.daily", "report");
        ExportSpec noFormat = new ExportSpec(null, null, "report.xlsx", null, "B2", List.of(),
                null, "Asia/Tokio", null, null, null, null, null, null);

        List<Violation> violations = ExportDeclarations.violations(step, noFormat, null);

        assertThat(violations).extracting(Violation::key).containsExactly("export.timezone");
    }

    @Test
    void anImportSourceIsJudgedByItsSurface() {
        // A file-import route mounts no request binder: only request.locale and the
        // principal resolve there, whatever input: (the row contract) declares; a poll job
        // has no request at all.
        Site importRoute = new Site("t", "route 'items.import'", Surface.FILE_IMPORT,
                Set.of("lang"), true, true);
        Site pollJob = Site.job("t", "orders.intake");

        assertThat(ExportDeclarations.violations(importRoute, importLocale("query.lang")))
                .singleElement().extracting(Violation::message).asString()
                .contains("'query.lang'", "a file-import binds none");
        assertThat(ExportDeclarations.violations(importRoute, importLocale("body.lang")))
                .hasSize(1);
        assertThat(ExportDeclarations.violations(importRoute, importLocale("request.locale")))
                .isEmpty();
        assertThat(ExportDeclarations.violations(importRoute,
                importLocale("principal.claim.locale"))).isEmpty();
        assertThat(ExportDeclarations.violations(pollJob, importLocale("principal.claim.locale")))
                .singleElement().extracting(Violation::message).asString()
                .contains("job 'orders.intake'", "a job has no request");
        assertThat(ExportDeclarations.violations(pollJob, importLocale("request.locale")))
                .hasSize(1);
        assertThat(ExportDeclarations.violations(pollJob, importLocale("de-DE"))).isEmpty();
    }

    private static ImportSpec importLocale(String locale) {
        return new ImportSpec("csv", List.of(), null, null, null, locale, null, null);
    }

    @Test
    void anImportLocaleAndItsColumnsAreJudgedByTheSameRules() {
        ImportSpec spec = new ImportSpec("Csv", List.of(
                ColumnSpec.of("fee", null, null, "number", "#,##0.00.00"),
                ColumnSpec.of("qty", null, "ZZZZ", null, null)), null, null, null, "de_DE",
                null, null);

        List<Violation> violations = ExportDeclarations.violations(ROUTE, spec);

        assertThat(violations).extracting(Violation::key).containsExactly("format",
                "import.locale", "import.columns[fee].format");
        assertThat(violations).extracting(Violation::kind).containsOnly(Kind.INVALID);
        assertThat(violations.get(1).message()).contains("import.locale", "'de_DE'");
        // The positional column: a workbook bound only where a workbook is read.
        assertThat(ExportDeclarations.violations(ROUTE, new ImportSpec("excel", List.of(
                ColumnSpec.of("qty", null, "ZZZZ", null, null)), null, null, null, null, null,
                null))).singleElement().extracting(Violation::key)
                .isEqualTo("import.columns[qty].column");
    }

    @Test
    void anInertKeyIsAnErrorForTheLinterAndAWarningForTheBoot() {
        ExportSpec spec = new ExportSpec("excel", null, null, null, null, List.of(), "ja_JP",
                null, null, null, null, null, null, true);

        List<Violation> violations = ExportDeclarations.violations(ROUTE, spec, null);

        // The locale's VALUE is not judged where nothing reads it: one inert finding, never a
        // refusal beside it.
        assertThat(violations).extracting(Violation::kind).containsOnly(Kind.INERT);
        assertThat(violations).extracting(Violation::key).containsExactly("export.bom",
                "export.locale");
        assertThat(violations).extracting(violation -> violation.code().toString())
                .containsOnly("TQL-YAML-1005");
        assertThat(violations.get(1).message()).contains("drives nothing in a workbook");
    }

    @Test
    void aTemplateOnCsvIsInertAndItsExistenceIsNotJudged(@TempDir Path dir) {
        ExportSpec csv = new ExportSpec("csv", null, "missing.xlsx", "S1", "B2", List.of(),
                null, null, null, null, null, null, null, null);

        List<Violation> violations = ExportDeclarations.violations(ROUTE, csv, dir);

        assertThat(violations).extracting(Violation::kind).containsOnly(Kind.INERT);
        assertThat(violations).extracting(Violation::key).containsExactly("export.sheet",
                "export.template");
        assertThat(violations.get(1).message()).contains("'missing.xlsx'", "reads no template");
    }

    @Test
    void aMissingOrUnusableTemplateIsRefusedWhereTheFormatReadsIt(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("present.xlsx"), "x");
        ExportSpec missing = new ExportSpec("excel", null, "missing.xlsx", null, null,
                List.of(), null, null, null, null, null, null, null, null);
        ExportSpec present = new ExportSpec("excel", null, "present.xlsx", null, "B2",
                List.of(), null, null, null, null, null, null, null, null);
        ExportSpec pdfWrongKind = new ExportSpec("pdf", null, "invoice.xlsx", null, null,
                List.of(), null, null, null, null, null, null, null, null);
        ExportSpec nul = new ExportSpec("excel", null, "bad\0name.xlsx", null, null,
                List.of(), null, null, null, null, null, null, null, null);

        assertThat(refusals(ROUTE, present)).isEmpty();
        assertThat(ExportDeclarations.violations(ROUTE, missing, dir)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1006");
                    assertThat(violation.kind()).isEqualTo(Kind.INVALID);
                    assertThat(violation.message()).contains("missing template: missing.xlsx");
                });
        assertThat(ExportDeclarations.violations(ROUTE, pdfWrongKind, dir)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1006");
                    assertThat(violation.message()).contains("'invoice.xlsx'", ".html");
                });
        // A NUL used to escape dir.resolve as an InvalidPathException on both sides.
        assertThat(ExportDeclarations.violations(ROUTE, nul, dir)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1006");
                    assertThat(violation.message()).contains("'bad?name.xlsx'", "not a file path");
                });
        assertThat(ExportDeclarations.violations(ROUTE, new ExportSpec("parquet", null,
                "missing.tpl", null, null, List.of(), null, null, null, null, null, null, null,
                null), dir)).singleElement().extracting(Violation::code)
                .isEqualTo(ExportDeclarations.UNUSABLE_TEMPLATE);
    }

    @Test
    void aReportModeDeclarationAndAnUntypedCsvColumnListAreAdvisories(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("r.xlsx"), "x");
        ExportSpec report = new ExportSpec("excel", null, "r.xlsx", null, null,
                List.of(ColumnSpec.of("when", null, null, "datetime", null)), null,
                "Asia/Tokio", null, null, null, null, null, null);
        ExportSpec untyped = new ExportSpec("csv", null, null, null, null,
                List.of(ColumnSpec.of("id"), ColumnSpec.of("name")), null, "Asia/Tokyo", null,
                null, null, null, null, null);
        ExportSpec typed = new ExportSpec("csv", null, null, null, null,
                List.of(ColumnSpec.of("id"), ColumnSpec.of("when", null, null, "datetime", null)),
                null, "Asia/Tokyo", null, null, null, null, null, null);

        // A jxls report never calls ColumnValues.zone: the mistyped zone is not judged there,
        // the advisory is the finding (served today, so no refusal).
        assertThat(ExportDeclarations.violations(ROUTE, report, dir)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.kind()).isEqualTo(Kind.ADVISORY);
                    assertThat(violation.message()).contains("jxls report", "timezone:",
                            "columns[].type:/format:");
                });
        assertThat(ExportDeclarations.violations(ROUTE, untyped, null)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.kind()).isEqualTo(Kind.ADVISORY);
                    assertThat(violation.message()).contains("none of the declared columns",
                            "cannot see derived columns");
                });
        assertThat(ExportDeclarations.violations(ROUTE, typed, null)).isEmpty();
    }

    @Test
    void aFollowUpWithoutItsStatementIsIncompleteOnAFileExportOnly() {
        ExportSpec noSql = new ExportSpec("csv", null, null, null, null, List.of(), null, null,
                new ExportSpec.AfterSpec("extract", null), null, null, null, null, null);
        ExportSpec noFile = new ExportSpec("csv", null, null, null, null, List.of(), null, null,
                new ExportSpec.AfterSpec("extract",
                        new io.tesseraql.yaml.model.Binding.SqlArm(null, "update", null, null,
                                null, null, null, null, null)),
                null, null, null, null, null);

        assertThat(refusals(FILE_EXPORT, noSql)).singleElement().satisfies(violation -> {
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1041");
            assertThat(violation.message()).contains("export.after", "after.sql");
        });
        assertThat(refusals(FILE_EXPORT, noFile)).hasSize(1);
        // A query-export's after: is the compiler's own refusal (TQL-ROUTE-3101) — the
        // predicate must not send the author to "add the statement" first.
        assertThat(refusals(ROUTE, noSql)).isEmpty();
        assertThat(refusals(Site.step("t", "j", "s"), noSql)).isEmpty();
    }

    @Test
    void aConfigKeyIsALiteralAndNeverASource() {
        assertThat(ExportDeclarations.configViolations("t", "tesseraql.files.timezone",
                "Asia/Tokio")).singleElement().extracting(Violation::message).asString()
                .contains("app 't'", "config", "tesseraql.files.timezone", "'Asia/Tokio'");
        assertThat(ExportDeclarations.configViolations("t", "tesseraql.files.locale", "ja_JP"))
                .hasSize(1);
        assertThat(ExportDeclarations.configViolations("t", "tesseraql.files.timezone",
                "query.tz")).singleElement().extracting(Violation::message).asString()
                .contains("request source expression", "no request");
        assertThat(ExportDeclarations.configViolations("t", "tesseraql.files.timezone",
                "Asia/Tokyo")).isEmpty();
        assertThat(ExportDeclarations.configViolations("t", "tesseraql.files.timezone", " "))
                .isEmpty();
    }

    @Test
    void theValueInAMessageIsBoundedAndControlStripped() {
        String forged = "Asia/Tokyo\nFORGED";
        assertThat(ExportDeclarations.bounded(forged)).isEqualTo("Asia/Tokyo?FORGED");
        assertThat(ExportDeclarations.bounded("A".repeat(300))).hasSize(43).endsWith("...");
        assertThat(refusals(ROUTE, export("csv", null, forged))).singleElement()
                .extracting(Violation::message).asString()
                .contains("Asia/Tokyo?FORGED").doesNotContain("\n");
        // Separators and overrides Character.isISOControl misses, and a cut that would split
        // a surrogate pair.
        assertThat(ExportDeclarations.bounded("a\u2028b\u2029c\u202Ed\u0000e"))
                .isEqualTo("a?b?c?d?e");
        assertThat(ExportDeclarations.bounded("x".repeat(39) + "😀tail"))
                .isEqualTo("x".repeat(39) + "😀...");
        assertThat(ExportDeclarations.bounded("lone\uD83Dend")).isEqualTo("lone?end");
    }

    @Test
    void everyAuthorControlledFragmentOfAMessageIsBounded() {
        // Every arm that echoes an author's string: the value, the column name, the derived
        // input name, the template, the route id — a 5,000-character value with line feeds
        // and a NUL never reaches a log line or a finding unbounded.
        String huge = "A".repeat(2000) + "\nFORGED LINE\n\0" + "B".repeat(3000);
        Site route = new Site("t", "route '" + ExportDeclarations.bounded(huge) + "'",
                Surface.FILE_EXPORT, Set.of(), true, true);
        List<Violation> all = new java.util.ArrayList<>();
        all.addAll(ExportDeclarations.violations(route, export("csv", null, "query." + huge),
                null));
        all.addAll(ExportDeclarations.violations(route, columns("csv",
                ColumnSpec.of(huge, null, null, "number", "#,##0.00.00" + huge),
                ColumnSpec.of("d", null, null, "date", "yyyy'" + huge),
                ColumnSpec.of("t", null, null, huge, null)), null));
        all.addAll(ExportDeclarations.violations(route, new ExportSpec("excel", null, huge,
                null, null, List.of(), null, null, null, null, null, null, null, null),
                Path.of(".")));
        all.addAll(ExportDeclarations.violations(route, new ExportSpec("csv", null, null, null,
                huge, List.of(ColumnSpec.of("c", null, huge, null, null)), huge, huge, null,
                null, null, null, null, null), null));
        all.addAll(ExportDeclarations.violations(Site.step("t", huge, huge),
                export("csv", null, huge), null));
        all.addAll(ExportDeclarations.configViolations(huge, "tesseraql.files.timezone", huge));

        assertThat(all).hasSizeGreaterThanOrEqualTo(10);
        assertThat(all).allSatisfy(violation -> {
            assertThat(violation.message()).hasSizeLessThan(500).doesNotContain("\n", "\0");
            assertThat(violation.key()).hasSizeLessThan(200);
        });
    }
}
