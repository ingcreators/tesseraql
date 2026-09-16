package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two boot refusals that used to be the JDK's or the scheduler's own sentence, wrapped as
 * "Failed to start TesseraQL runtime" and naming neither the key nor the application
 * (docs/audit-low-leads.md slice 8, XD-07f): a {@code tesseraql.security.conditions.zone} that
 * is not a zone, and a job {@code schedule.cron} the scheduler cannot fire. Both are coded now,
 * from the predicate the linter reports from, and propagate raw through the boot's wrapper the
 * way every {@link TqlException} does ({@link BootFailureReleaseTest}'s contract). The pools
 * open before either is read, so the refusal is reachable only with a database.
 */
@Testcontainers
class BootRefusalShapesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void aMisspeltConditionsZoneIsRefusedNamingTheKey(@TempDir Path dir) throws Exception {
        Path appHome = appHome(dir, "boot-zone", """
                  security:
                    conditions:
                      zone: Asia/Tokio
                """);

        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4147")
                .hasMessageContaining("tesseraql.security.conditions.zone: 'Asia/Tokio'")
                .hasMessageContaining("not a time-zone id the JDK knows")
                .hasMessageNotContaining("Failed to start");
    }

    @Test
    void aCronTheSchedulerCannotFireIsRefusedNamingTheJobAndItsFile(@TempDir Path dir)
            throws Exception {
        Path appHome = appHome(dir, "boot-cron", "");
        Path job = appHome.resolve("batch/nightly");
        Files.createDirectories(job);
        Files.writeString(job.resolve("close.sql"), "select 1\n");
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: nightly.close
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0 3 * * *"
                pipeline:
                  - id: close
                    sql:
                      file: close.sql
                      mode: query
                """);

        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1068")
                .hasMessageContaining("Job 'nightly.close' schedule.cron: '0 3 * * *'")
                .hasMessageContaining("not a cron expression the scheduler can fire")
                .hasMessageContaining("job.yml")
                .hasMessageNotContaining("Failed to start");
    }

    private static Path appHome(Path dir, String name, String tesseraqlTail) throws IOException {
        Path target = dir.resolve(name);
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
                %s""".formatted(name, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), tesseraqlTail));
        return target;
    }
}
