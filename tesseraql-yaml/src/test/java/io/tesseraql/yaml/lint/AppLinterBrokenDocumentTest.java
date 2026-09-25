package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A document that does not parse is one finding at that document, and the rest of the
 * application is still linted (docs/audit-low-leads.md slice 8, XH-22/XD-07g). The linter
 * used to load strictly, so the first such document escaped {@code lint} as the parser's
 * exception: no finding, no JSON document for the editor, every other finding hidden — on
 * the shape an editor produces most, an empty file whose header is not typed yet.
 *
 * <p>The fixtures are the shapes the origin measured: an empty route, a job whose
 * {@code as:} is an unquoted flow mapping, an empty workflow, a header-less mcp document —
 * beside one clean route carrying a finding of its own, which proves the run went on.
 */
class AppLinterBrokenDocumentTest {

    private static Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        // The clean route, with a finding the run must still reach: an unknown recipe.
        Files.createDirectories(dir.resolve("web/ok"));
        Files.writeString(dir.resolve("web/ok/get.yml"), """
                version: tesseraql/v1
                id: ok
                kind: route
                recipe: no-such-recipe
                security:
                  auth: public
                """);
        return dir;
    }

    /**
     * A second YAML document in one application file is refused (docs/jackson-3.md S2): Jackson
     * 2 read the first and dropped the rest, so a stray `---` hid everything after it.
     */
    @Test
    void aSecondDocumentInOneFileIsRefused(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/twice"));
        Files.writeString(dir.resolve("web/twice/get.yml"), """
                version: tesseraql/v1
                id: twice
                kind: route
                recipe: query-json
                security:
                  auth: public
                ---
                id: the-second-document
                """);

        assertThat(at(new AppLinter().lint(dir), "web/twice/get.yml")).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-YAML-1001");
                    assertThat(finding.message()).contains("Trailing token");
                });
    }

    private static List<LintFinding> at(List<LintFinding> findings, String source) {
        return findings.stream().filter(finding -> source.equals(finding.source())).toList();
    }

    @Test
    void everyBrokenDocumentIsOneFindingAndTheRestIsStillLinted(@TempDir Path dir)
            throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/empty"));
        Files.writeString(dir.resolve("web/empty/get.yml"), "");
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0 0 3 * * ?"
                pipeline:
                  - id: send
                    push:
                      to: s3://bucket
                      as: {nope}
                """);
        Files.createDirectories(dir.resolve("workflow"));
        Files.writeString(dir.resolve("workflow/ticket.yml"), "");
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/lookup.yml"), "kind: tool\n");
        Files.createDirectories(dir.resolve("consume/orders"));
        Files.writeString(dir.resolve("consume/orders/get.yml"), "version: tesseraql/v1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(at(findings, "web/empty/get.yml")).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1001");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("No content to map")
                    .doesNotContain("TQL-YAML-1001").doesNotContain(dir.toString());
        });
        assertThat(at(findings, "batch/report/job.yml")).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1001");
            assertThat(finding.message()).contains("Cannot deserialize");
            // The parser's own location, lifted into the finding so the editor jumps there.
            assertThat(finding.line()).isEqualTo(12);
            assertThat(finding.column()).isNotNull();
        });
        assertThat(at(findings, "workflow/ticket.yml")).singleElement()
                .satisfies(finding -> assertThat(finding.code()).isEqualTo("TQL-YAML-1001"));
        assertThat(at(findings, "mcp/lookup.yml")).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1001");
            assertThat(finding.message()).contains("Missing required field 'version'");
        });
        assertThat(at(findings, "consume/orders/get.yml")).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1001");
            assertThat(finding.message()).contains("Missing required field 'id'");
        });
        // The run went on: the clean route's own finding is there.
        assertThat(at(findings, "web/ok/get.yml")).extracting(LintFinding::code)
                .contains("TQL-YAML-1002");
    }

    /**
     * What every document resolves through — the configuration, a shared definition — still
     * fails the load whole; the lint's answer is then that one finding, at the file, rather
     * than an exception out of {@code lint}.
     */
    @Test
    void aFailureOfTheWholeLoadIsOneFindingAtTheFileItNames(@TempDir Path dir)
            throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/fields.yml"), "domains: [unclosed\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.code()).startsWith("TQL-");
            assertThat(finding.source()).isEqualTo("domains/fields.yml");
        });
    }
}
