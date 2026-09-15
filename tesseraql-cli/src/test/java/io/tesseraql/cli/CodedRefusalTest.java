package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * A coded exception that escapes a command is the framework's own diagnosis and prints as its
 * message with exit 2, never as a stack trace with exit 1 (docs/cli-surface.md decision 10a).
 * Measured before the shaper learned the shape: on a route document that does not parse,
 * twenty-two verbs printed a 55-line trace and exit 1 while {@code dev}, {@code host} and
 * {@code mcp} printed the sentence and 2; {@code tesseraql package}'s two lock refusals were
 * traces too, the item docs/module-channel.md decision 9 observed and did not change. These
 * drive the commands end to end through the same {@code CommandLine} the binary runs.
 */
class CodedRefusalTest {

    /** The headline decision 9 filed: modules declared, no lock. */
    @Test
    void packageWithModulesDeclaredAndNoLockIsTheCodedSentenceAndExit2(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: unlocked
                  modules:
                    - info.picocli:picocli
                """);

        Run run = run("package", "--app", dir.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr().strip().lines()).hasSize(1);
        assertThat(run.stderr()).startsWith("TQL-APP-4218: Application '" + dir.getFileName()
                + "' declares tesseraql.modules but has no modules.lock")
                .contains("tesseraql modules resolve --app " + dir)
                .doesNotContain("Exception").doesNotContain("\tat ");
        assertThat(dir.resolve("work")).as("nothing was packaged").doesNotExist();
    }

    /** Decision 9's other sentence: a lock naming an artifact the runtime carries. */
    @Test
    void packageWithALockNamingACarriedArtifactIsTheCodedSentenceAndExit2(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: carried
                  modules:
                    - info.picocli:picocli
                """);
        Files.writeString(dir.resolve("modules.lock"), """
                {
                  "lockfileVersion" : 1,
                  "modules" : [ "info.picocli:picocli" ],
                  "artifacts" : [ {
                    "coordinate" : "org.slf4j:slf4j-api:2.0.18",
                    "sha256" : "0000000000000000000000000000000000000000000000000000000000000000"
                  } ]
                }
                """);

        Run run = run("package", "--app", dir.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr().strip().lines()).hasSize(1);
        assertThat(run.stderr()).startsWith("TQL-APP-4219: Application '" + dir.getFileName()
                + "' has a modules.lock naming 1 artifact(s) the runtime already carries")
                .contains("org.slf4j:slf4j-api:2.0.18")
                .doesNotContain("Exception").doesNotContain("\tat ");
    }

    /**
     * The CLI-wide shape, on the verb with the least between the load and the answer: a route
     * document that does not parse is the parser's coded sentence — its location line included,
     * because the sentence is what the shaper prints, not one line of it — and exit 2, the
     * answer {@code dev} already gave the same file.
     */
    @Test
    void aRouteThatDoesNotParseIsTheParsersSentenceAndExit2OnEveryVerb(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.createDirectories(dir.resolve("web/ping"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: broken
                """);
        Files.writeString(dir.resolve("web/ping/get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                input: [unclosed
                sources:
                  main:
                    sql:
                      file: ping.sql
                """);

        Run run = run("routes", "--app", dir.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr()).startsWith("TQL-YAML-1001: Failed to parse route YAML")
                .contains("web/ping/get.yml")
                .doesNotContain("\tat ").doesNotContain("io.tesseraql.core.error.TqlException");
    }

    private static Run run(String... args) {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));
        int exit = commandLine.execute(args);
        return new Run(exit, err.toString());
    }

    private record Run(int exit, String stderr) {
    }
}
