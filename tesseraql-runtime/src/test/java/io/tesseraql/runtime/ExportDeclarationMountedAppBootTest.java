package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.operations.batch.JobStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The boot twin of the export-declaration predicate on the two job arms
 * {@link ExportDeclarationBootTest} does not walk (docs/export-declarations.md decision 1): a
 * mounted app's job step, judged at the second job-map fill and refused naming the mounted app,
 * and a missing template on a job step. The mounted app is a test-scoped
 * {@link MountedProbeAppSourceProvider}, present only while the property names a directory.
 */
@Testcontainers
class ExportDeclarationMountedAppBootTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @AfterEach
    void unmount() {
        System.clearProperty(MountedProbeAppSourceProvider.PROPERTY);
    }

    // The second job-map fill is the mounted app's: a test-scoped AppSourceProvider mounts a
    // directory, so a refusal there is pinned separately from the main app's.
    @Test
    void aMistypedZoneOnAMountedAppsJobStepRefusesTheBootNamingTheMountedApp(@TempDir Path dir)
            throws Exception {
        Path main = mainApp(dir, "mount-host-a");
        Path mounted = mountedApp(dir, "      format: csv\n      timezone: Asia/Tokio\n");
        System.setProperty(MountedProbeAppSourceProvider.PROPERTY, mounted.toString());

        assertThatThrownBy(() -> TesseraqlRuntime.start(main, 0))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("app 'mounted-probe'")
                .hasMessageContaining("job 'mounted.report' step 'report'")
                .hasMessageContaining("'Asia/Tokio'");
    }

    @Test
    void theValidMountedTwinBootsAndRunsItsJob(@TempDir Path dir) throws Exception {
        Path main = mainApp(dir, "mount-host-b");
        Path mounted = mountedApp(dir, "      format: csv\n      timezone: Asia/Tokyo\n");
        System.setProperty(MountedProbeAppSourceProvider.PROPERTY, mounted.toString());

        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(main, 0)) {
            // hazard 19: the twin proves the mounted job is registered, not just that it boots
            assertThat(runtime.runJob("mounted.report", Map.of()).status())
                    .isEqualTo(JobStatus.COMPLETED);
        }
    }

    // Red on a job arm that passes no directory: a missing template on a step would boot.
    @Test
    void aMissingTemplateOnAJobStepRefusesTheBootNamingTheStep(@TempDir Path dir)
            throws Exception {
        Path main = mainAppWithJob(dir, "template-host",
                "      format: excel\n      template: nowhere.xlsx\n");

        assertThatThrownBy(() -> TesseraqlRuntime.start(main, 0))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-YAML-1006")
                .hasMessageContaining("job 'report.daily' step 'report'")
                .hasMessageContaining("nowhere.xlsx");
    }

    private static Path mainApp(Path dir, String name) throws IOException {
        Path target = dir.resolve(name);
        writeConfig(target, name);
        return target;
    }

    private static Path mainAppWithJob(Path dir, String name, String exportBody)
            throws IOException {
        Path target = mainApp(dir, name);
        writeJob(target, "report.daily", exportBody);
        return target;
    }

    private static Path mountedApp(Path dir, String exportBody) throws IOException {
        Path target = dir.resolve("mounted-probe");
        Files.createDirectories(target);
        writeJob(target, "mounted.report", exportBody);
        return target;
    }

    private static void writeJob(Path app, String jobId, String exportBody) throws IOException {
        Files.createDirectories(app.resolve("batch/report"));
        Files.writeString(app.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: %s
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: report.sql
                      mode: query
                    export:
                %s""".formatted(jobId, exportBody));
        Files.writeString(app.resolve("batch/report/report.sql"),
                "select 1 as id, now() as created\n");
    }

    private static void writeConfig(Path target, String name) throws IOException {
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(name, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }
}
