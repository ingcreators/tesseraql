package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The unknown-key walk goes as deep as the model does (docs/lint-restructure.md decision 3).
 *
 * <p>It used to check a document's own keys plus the blocks someone had registered in a map, so
 * the interior of {@code security:} was silently tolerant — a {@code polcy:} inside it dropped
 * the authorization declaration exactly the way a {@code securty:} beside it dropped the whole
 * block, and only one of the two was reported. The document families that never got the walk at
 * all — prompts, rule sets, decisions, calendars — are here for the same reason.
 */
class AppLinterNestedKeysTest {

    private Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        return dir;
    }

    /** The interior of a fixed-shape block nobody had registered. */
    @Test
    void flagsATypoInsideTheSecurityBlock(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  polcy: items.read
                sources:
                  main:
                    text: select 1
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043") && !f.isError()
                && f.message().contains("security.polcy"));
        // The keys beside it are the block's own, and stay quiet.
        assertThat(findings).noneMatch(f -> f.message().contains("security.auth"));
    }

    /** A nested shape reached through a map the author names: one source's binding. */
    @Test
    void flagsATypoInsideANamedSource(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: list.sql
                      mod: query
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        Files.writeString(dir.resolve("web/api/items/list.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.message().contains("sources.main.sql.mod"));
    }

    /** A prompt is not a route, which is why nothing checked it. */
    @Test
    void flagsATypoInAPromptDocument(@TempDir Path dir) throws Exception {
        app(dir);
        Path mcp = dir.resolve("mcp");
        Files.createDirectories(mcp);
        Files.writeString(mcp.resolve("welcome.txt.tpl"), "Hello [(${name})]\n");
        Files.writeString(mcp.resolve("welcome.yml"), """
                version: tesseraql/v1
                id: draft-welcome
                kind: prompt
                description: Draft a welcome message.
                input:
                  name:
                    type: string
                    requred: true
                templat: welcome.txt.tpl
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1043")
                        && f.source().contains("welcome.yml"))
                .anyMatch(f -> f.message().contains("templat"))
                .anyMatch(f -> f.message().contains("input.name.requred"));
    }

    /** A clean prompt carries no findings: its keys are the ones the loader reads. */
    @Test
    void acceptsAWellFormedPrompt(@TempDir Path dir) throws Exception {
        app(dir);
        Path mcp = dir.resolve("mcp");
        Files.createDirectories(mcp);
        Files.writeString(mcp.resolve("welcome.txt.tpl"), "Hello [(${name})]\n");
        Files.writeString(mcp.resolve("welcome.yml"), """
                version: tesseraql/v1
                id: draft-welcome
                kind: prompt
                description: Draft a welcome message.
                input:
                  name:
                    type: string
                    required: true
                    description: The new user's name.
                template: welcome.txt.tpl
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1043"));
    }

    /** Decisions parse into ignoreUnknown records, so a misspelled hit policy fell back silently. */
    @Test
    void flagsATypoInADecisionsDocument(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("decisions"));
        Files.writeString(dir.resolve("decisions/pricing.yml"), """
                version: tesseraql/v1
                decisions:
                  pricing:
                    hitPolcy: first
                    inputs:
                      tier: { type: string }
                    outputs:
                      rate: { type: number }
                    rows:
                      - when: { tier: gold }
                        outputs: { rate: 0.9 }
                      - outputs: { rate: 1.0 }
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.source().contains("pricing.yml")
                && f.message().contains("decisions.pricing.hitPolcy"));
    }

    /** Calendars too: a misspelled holidays block left a calendar with no holidays at all. */
    @Test
    void flagsATypoInACalendarsDocument(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("calendars"));
        Files.writeString(dir.resolve("calendars/main.yml"), """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [saturday, sunday]
                    holidys:
                      dates: [2026-01-01]
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.source().contains("main.yml")
                && f.message().contains("calendars.jp-banking.holidys"));
    }

    /** And shared rule sets: a dropped message key leaves the refusal with no text. */
    @Test
    void flagsATypoInARuleSetDocument(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("rules/catalog.yml"), """
                version: tesseraql/v1
                rules:
                  quantityStaysSane:
                    rule: "params.delta >= -10000"
                    code: out-of-range
                    mesage: rule.quantity.range
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.source().contains("catalog.yml")
                && f.message().contains("rules.quantityStaysSane.mesage"));
    }
}
