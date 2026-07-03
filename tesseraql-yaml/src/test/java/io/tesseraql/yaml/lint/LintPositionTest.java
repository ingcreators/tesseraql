package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lint findings gain best-effort positions (authoring feedback, roadmap Phase 43): document
 * rules point at the first occurrence of the offending key so editors can jump to it, and the
 * CLI/Studio render {@code source:line}.
 */
class LintPositionTest {

    @Test
    void documentRulesCarryTheOffendingKeysLine(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/x.sql"), "select 1 as x\n");
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: no-such-recipe
                input:
                  q:
                    type: string
                    pattern: "["
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        LintFinding recipe = findings.stream()
                .filter(f -> "TQL-YAML-1002".equals(f.code())).findFirst().orElseThrow();
        assertThat(recipe.line()).isEqualTo(4);
        assertThat(recipe.location()).isEqualTo("web/api/x/get.yml:4");

        LintFinding pattern = findings.stream()
                .filter(f -> "TQL-YAML-1012".equals(f.code())).findFirst().orElseThrow();
        assertThat(pattern.line()).isEqualTo(6);

        // A negative statement timeout is rejected (0 is the explicit opt-out).
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                  timeoutSeconds: -1
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        assertThat(new AppLinter().lint(dir))
                .anySatisfy(f -> {
                    assertThat(f.code()).isEqualTo("TQL-YAML-1021");
                    assertThat(f.line()).isEqualTo(7);
                });

        // Position-less findings render the bare source (the pre-positions shape).
        assertThat(new LintFinding("X", "warning", "web/a.yml", "m").location())
                .isEqualTo("web/a.yml");
        assertThat(new LintFinding("X", "warning", "web/a.yml", "m", 7, 3).location())
                .isEqualTo("web/a.yml:7:3");
    }
}
