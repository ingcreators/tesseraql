package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around the chunk step (docs/batch-platform.md track C). The restart contract lives in
 * the reader's SQL — order and the {@code chunk.after} bind — and only the build can see it.
 */
class AppLinterChunkTest {

    private Path app(@TempDir Path dir, String chunkBody, String readerSql) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/load"));
        Files.writeString(dir.resolve("batch/load/job.yml"), """
                version: tesseraql/v1
                id: load.orders
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: revalue
                    chunk:
                %s
                """.formatted(chunkBody));
        if (readerSql != null) {
            Files.writeString(dir.resolve("batch/load/reader.sql"), readerSql);
        }
        Files.writeString(dir.resolve("batch/load/writer.sql"),
                "update orders set total = total where id = /* row.id */ 1\n");
        return dir;
    }

    @Test
    void aReaderWithoutOrderByIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      reader:\n        sql:\n          file: reader.sql\n      writer:\n        sql:\n          file: writer.sql",
                "select id from orders where id > /* chunk.after */ 0\n"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4207");
            assertThat(finding.severity()).isEqualTo("error");
        });
    }

    @Test
    void aReaderThatNeverBindsTheCheckpointIsAWarning(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      reader:\n        sql:\n          file: reader.sql\n      writer:\n        sql:\n          file: writer.sql",
                "select id from orders order by id\n"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4208");
            assertThat(finding.severity()).isEqualTo("warning");
        });
        assertThat(findings).noneMatch(finding -> "TQL-BATCH-4207".equals(finding.code()));
    }

    @Test
    void aChunkWithoutReaderOrWriterAndBadNumbersAreErrors(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      writer:\n        sql:\n          file: writer.sql\n      commitEvery: 5",
                null));
        assertThat(findings).anySatisfy(
                finding -> assertThat(finding.code()).isEqualTo("TQL-BATCH-4206"));

        findings = new AppLinter().lint(app(dir,
                "      reader:\n        sql:\n          file: reader.sql\n      writer:\n        sql:\n          file: writer.sql\n"
                        + "      commitEvery: 0\n      onError: skip\n      skipLimit: -1",
                "select id from orders where id > /* chunk.after */ 0 order by id\n"));
        assertThat(findings.stream().filter(f -> "TQL-BATCH-4206".equals(f.code()))).hasSize(2);
    }

    @Test
    void aWellFormedChunkStepIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      reader:\n        sql:\n          file: reader.sql\n      writer:\n        sql:\n          file: writer.sql\n"
                        + "      key: id\n      commitEvery: 500\n"
                        + "      onError: skip\n      skipLimit: 10",
                "select id from orders\n/*%if chunk.after != null */\n"
                        + "where id > /* chunk.after */ 0\n/*%end*/\norder by id\n"));

        assertThat(findings).noneMatch(finding -> finding.code().startsWith("TQL-BATCH-42"));
        assertThat(findings).noneMatch(finding -> "TQL-FIELD-2004".equals(finding.code()));
    }

    @Test
    void aStepDeclaringChunkAndSqlTogetherIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/load"));
        Files.writeString(dir.resolve("batch/load/job.yml"), """
                version: tesseraql/v1
                id: load.orders
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: revalue
                    sql:
                      file: writer.sql
                      mode: update
                    chunk:
                      reader:
                        sql:
                          file: reader.sql
                      writer:
                        sql:
                          file: writer.sql
                """);
        Files.writeString(dir.resolve("batch/load/reader.sql"), "select 1 order by 1\n");
        Files.writeString(dir.resolve("batch/load/writer.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-FIELD-2004");
            assertThat(finding.message()).contains("chunk:");
        });
    }
}
