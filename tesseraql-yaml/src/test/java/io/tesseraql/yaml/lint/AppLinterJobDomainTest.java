package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A job's declarations are judged by the families that judge a route's
 * (docs/temporal-semantics.md decision 27, audit lead 27): the domain lint sees a job's
 * references and loosenings, and the declared-kind input arm sees a job's {@code input:}.
 * Before, every one of these walked routes, consumers and tools only.
 */
class AppLinterJobDomainTest {

    private Path app(@TempDir Path dir, String input) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/batch.yml"), """
                version: tesseraql/v1
                domains:
                  batch_count:
                    type: integer
                    min: 1
                    max: 500
                """);
        Files.createDirectories(dir.resolve("batch/nightly"));
        Files.writeString(dir.resolve("batch/nightly/job.yml"), """
                version: tesseraql/v1
                id: nightly
                kind: job
                recipe: batch-pipeline
                input:
                  %s
                pipeline:
                  - id: main
                    sql:
                      file: main.sql
                      mode: update
                """.formatted(input));
        Files.writeString(dir.resolve("batch/nightly/main.sql"),
                "update events set touched = 1 where id <= /* count */ 1\n");
        return dir;
    }

    @Test
    void aJobOnlyDomainIsAReferenceAndLintsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "count: { domain: batch_count, required: true }"));

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4611"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4610"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1064"));
    }

    @Test
    void aJobInputLooseningItsDomainWarns(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "count: { domain: batch_count, max: 5000 }"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-4610") && !f.isError()
                && f.source().equals("batch/nightly/job.yml")
                && f.message().contains("Field 'count' loosens domain 'batch_count': max 5000"
                        + " > 500"));
    }

    @Test
    void aJobInputOfAKindNoRequestBindsIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "count: { type: json }"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1064") && f.isError()
                && f.source().equals("batch/nightly/job.yml")
                && f.message().contains("job 'nightly' input.count.type: 'json' is not a type a"
                        + " request binds"));
    }
}
