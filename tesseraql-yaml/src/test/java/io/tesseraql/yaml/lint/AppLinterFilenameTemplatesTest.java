package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A download name's placeholders, as findings (docs/route-filename-placeholders.md decision
 * 5): {@code TQL-YAML-1076} on a route's {@code export.filename}, {@code response.file.filename}
 * and {@code response.stream.filename} and on a job step's {@code export.filename}, each arm
 * at its line; the resolvable spellings — a declared input, the URL's parameter, a declared
 * source on a file response, the step context's roots — stay clean. Before this rule every
 * spelling passed lint and reached the wire braces on.
 */
class AppLinterFilenameTemplatesTest {

    private static final String CODE = "TQL-YAML-1076";

    /** A query-export under {@code /api/orders/{id}/export} declaring {@code month}, plus a print page. */
    private static Path app(Path dir, String exportFilename, String fileFilename) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path export = dir.resolve("web/api/orders/{id}/export");
        Files.createDirectories(export);
        Files.writeString(export.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.export
                kind: route
                recipe: query-export
                security:
                  auth: public
                input:
                  month: { type: string, required: false }
                export:
                  format: csv
                  filename: "%s"
                sources:
                  main:
                    sql:
                      file: export.sql
                """.formatted(exportFilename));
        Files.writeString(export.resolve("export.sql"), "select 1 as n\n");
        Path print = dir.resolve("web/orders/{id}/print");
        Files.createDirectories(print);
        Files.writeString(print.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.print
                kind: route
                recipe: page
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: total.sql
                response:
                  file:
                    template: receipt.txt
                    contentType: text/plain
                    filename: "%s"
                """.formatted(fileFilename));
        Files.writeString(print.resolve("total.sql"), "select 1 as total\n");
        Files.writeString(print.resolve("receipt.txt"), "Total: [(${total})]\n");
        return dir;
    }

    private static List<LintFinding> findings(Path dir) {
        return new AppLinter().lint(dir).stream().filter(f -> CODE.equals(f.code())).toList();
    }

    @Test
    void aResolvableNameOnEitherSiteIsClean(@TempDir Path dir) throws Exception {
        assertThat(findings(app(dir, "orders-{params.month}-{path.id}-{key}.csv",
                "order-{path.id}-{main.rowCount}-{tenant}.txt"))).isEmpty();
    }

    @Test
    void eachArmIsAnErrorAtItsLineNamingTheRouteTheKeyAndThePlaceholder(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = findings(app(dir,
                "orders-{now}-{params.year}-{batch.business-date}.csv",
                "order-{path.orderId}.txt"));
        assertThat(findings).hasSize(4).allSatisfy(f -> assertThat(f.isError()).isTrue());
        assertThat(findings).filteredOn(f -> f.source().endsWith("export/get.yml"))
                .extracting(LintFinding::message)
                .satisfiesExactlyInAnyOrder(
                        m -> assertThat(m).contains("route 'orders.export'", "export.filename:",
                                "{now}", "names no request root", "would render _"),
                        m -> assertThat(m).contains("{params.year}",
                                "does not declare under input:"),
                        m -> assertThat(m).contains("{batch.business-date}",
                                "not a dotted path"));
        assertThat(findings).filteredOn(f -> f.source().endsWith("export/get.yml"))
                .allSatisfy(f -> assertThat(f.line()).isNotNull());
        assertThat(findings).filteredOn(f -> f.source().endsWith("print/get.yml"))
                .singleElement().satisfies(f -> {
                    assertThat(f.message()).contains("route 'orders.print'",
                            "response.file.filename:", "{path.orderId}",
                            "path parameter the route's URL does not declare");
                    assertThat(f.line()).isNotNull();
                });
    }

    @Test
    void aPrincipalOnAPublicRouteAndASourceOnAnExportAreRefused(@TempDir Path dir)
            throws Exception {
        // The export's sources have not run when its name is fixed, so a source name is not a
        // root there — it is one on the file response, which renders after them.
        assertThat(findings(app(dir, "{main.rowCount}.csv", "{principal.subject}.txt")))
                .extracting(LintFinding::message)
                .satisfiesExactlyInAnyOrder(
                        m -> assertThat(m).contains("export.filename:", "{main.rowCount}",
                                "names no request root"),
                        m -> assertThat(m).contains("response.file.filename:",
                                "{principal.subject}", "principal on an authenticated route"));
    }

    @Test
    void aJobStepsExportNameIsJudgedAgainstTheStepContextAndTheJobsInputs(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"),
                """
                        version: tesseraql/v1
                        id: report.daily
                        kind: job
                        recipe: batch-pipeline
                        input:
                          region: { type: string, required: false }
                        pipeline:
                          - id: report
                            export:
                              format: csv
                              filename: "report-{batch.businessDate}-{params.region}-{params.area}-{query.x}.csv"
                            sql:
                              file: report.sql
                              mode: query
                        """);
        Files.writeString(dir.resolve("batch/report/report.sql"), "select 1 as n\n");
        assertThat(findings(dir)).extracting(LintFinding::message)
                .satisfiesExactlyInAnyOrder(
                        m -> assertThat(m).contains("job 'report.daily' step 'report'",
                                "export.filename:", "{params.area}",
                                "the job does not declare under input:"),
                        m -> assertThat(m).contains("{query.x}", "names no job context root",
                                "params, steps, batch, tenant"));
    }
}
