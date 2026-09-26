package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export that renders dates in the JVM's zone is warned about (docs/deployment-decisions.md
 * decision 2, {@code TQL-YAML-1116}): the JVM's zone is the developer's in development and UTC in
 * the container image, so the same export differs between the two. The zone reaches a column
 * typed {@code date} or {@code datetime} on csv or pdf, and every temporal cell of a workbook.
 */
class AppLinterExportZoneTest {

    private static final String UNDECLARED_ZONE = "TQL-YAML-1116";

    private static final String DATED = """
              format: csv
              columns:
                - name: shipped_at
                  type: datetime
            """;

    @Test
    void aRouteExportWithADatedColumnAndNoZoneIsWarnedAbout(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "", DATED));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo(UNDECLARED_ZONE);
            assertThat(finding.isError()).as("a warning").isFalse();
            assertThat(finding.message()).contains("shipped_at", "tesseraql.files.timezone",
                    "UTC in the container image");
        });
    }

    @Test
    void eitherZoneSilencesIt(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir.resolve("own"), "",
                DATED + "  timezone: Asia/Tokyo\n")))
                .noneMatch(finding -> UNDECLARED_ZONE.equals(finding.code()));
        assertThat(new AppLinter().lint(route(dir.resolve("app"),
                "  files:\n    timezone: Asia/Tokyo\n", DATED)))
                .noneMatch(finding -> UNDECLARED_ZONE.equals(finding.code()));
    }

    /** csv and pdf render an untyped value as the driver's text, so no zone reaches it. */
    @Test
    void aCsvExportWithNoTypedDateIsSilent(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir, "", "  format: csv\n")))
                .noneMatch(finding -> UNDECLARED_ZONE.equals(finding.code()));
    }

    /** A workbook grid renders every temporal cell in the zone, typed or not. */
    @Test
    void aWorkbookGridIsWarnedAboutWithoutATypedColumn(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(route(dir, "", "  format: excel\n")))
                .anySatisfy(finding -> {
                    assertThat(finding.code()).isEqualTo(UNDECLARED_ZONE);
                    assertThat(finding.message()).contains("every date and time cell");
                });
    }

    @Test
    void aJobExportStepIsWarnedAboutToo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: report.sql
                      mode: query
                    export:
                      format: csv
                      columns:
                        - name: shipped_at
                          type: date
                """);
        Files.writeString(dir.resolve("batch/report/report.sql"),
                "select shipped_at from orders\n");

        assertThat(new AppLinter().lint(dir)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo(UNDECLARED_ZONE);
            assertThat(finding.message()).contains("shipped_at");
        });
    }

    private static Path route(Path dir, String config, String exportBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n" + config);
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/dump.sql"),
                "select id, shipped_at from orders\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.dump
                kind: route
                recipe: file-export
                method: GET
                path: /api/orders/dump
                sources:
                  main:
                    sql:
                      file: dump.sql
                export:
                """ + exportBody);
        return dir;
    }
}
