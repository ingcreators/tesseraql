package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file column's {@code domain:} (docs/temporal-semantics.md decision 25): a reference the
 * domain lint counts, on a route and — since decision 28 — on a job.
 */
class AppLinterColumnDomainTest {

    private Path app(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/fields.yml"), """
                version: tesseraql/v1
                domains:
                  held_date:
                    type: date
                    format: yyyy/MM/dd
                """);
        Path export = dir.resolve("web/api/events/export");
        Files.createDirectories(export);
        Files.writeString(export.resolve("post.yml"), """
                version: tesseraql/v1
                id: events.export
                kind: route
                recipe: file-export
                security:
                  auth: public
                export:
                  format: csv
                  filename: events.csv
                  columns:
                    - name
                    - { name: held_on, domain: held_date }
                sources:
                  main:
                    sql:
                      file: select.sql
                """);
        Files.writeString(export.resolve("select.sql"), "select name, held_on from events\n");
        return dir;
    }

    @Test
    void aColumnsDomainReferenceCountsAndLintsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir));

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4611"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1063"));
    }

    /**
     * A job's columns resolve their domains too (docs/temporal-semantics.md decision 28, which
     * lifted F-B's refusal once a job's own domains were resolved): the reference counts and
     * nothing refuses it.
     */
    @Test
    void aDomainOnAJobsColumnResolvesLikeARoutes(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        Files.createDirectories(app.resolve("batch/report"));
        Files.writeString(app.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: dump
                    sql:
                      file: select.sql
                      mode: query
                    export:
                      format: csv
                      filename: report.csv
                      columns:
                        - name
                        - { name: held_on, domain: held_date }
                """);
        Files.writeString(app.resolve("batch/report/select.sql"),
                "select name, held_on from events\n");

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1063")
                && f.source().equals("batch/report/job.yml"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4611"));
    }
}
