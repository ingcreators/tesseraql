package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The release-diff command (roadmap Phase 46): two trees in, a deterministic report out. */
class ReleaseDiffCommandTest {

    private void app(Path dir, String id) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Path api = dir.resolve("web/api/" + id);
        Files.createDirectories(api);
        Files.writeString(api.resolve(id + ".sql"), "select 1 as x\n");
        Files.writeString(api.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                sql:
                  file: %s.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """.formatted(id, id));
    }

    @Test
    void printsTheMarkdownReportAndWritesTheOutFile(@TempDir Path base, @TempDir Path next,
            @TempDir Path out) throws Exception {
        app(base, "things");
        app(next, "things");
        app(next, "orders");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            int exit = new CommandLine(new ReleaseDiffCommand()).execute(
                    "--app", next.toString(), "--baseline", base.toString(),
                    "--out", out.resolve("diff.md").toString());
            assertThat(exit).isZero();
        } finally {
            System.setOut(original);
        }
        String printed = captured.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("# Release diff").contains("ADDED orders");
        assertThat(Files.readString(out.resolve("diff.md"))).contains("ADDED orders");
    }
}
