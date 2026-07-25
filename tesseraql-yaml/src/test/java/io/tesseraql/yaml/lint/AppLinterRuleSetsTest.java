package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around shared validation rules (docs/validation-rule-sets.md).
 *
 * <p>The design promised a warning for a route-local rule that says what a shared one already
 * says — the copy-paste rule sets exist to replace — and it was never implemented, so the one
 * situation the feature was built to catch was the one it stayed silent about.
 */
class AppLinterRuleSetsTest {

    private static final String SANE = "params.delta >= -10000 && params.delta <= 10000";

    private Path app(@TempDir Path dir, String validate) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("rules/catalog.yml"), """
                version: tesseraql/v1

                rules:
                  quantityStaysSane:
                    rule: "%s"
                    code: out-of-range
                """.formatted(SANE));
        Files.createDirectories(dir.resolve("web/products/adjust"));
        Files.writeString(dir.resolve("web/products/adjust/post.yml"), """
                version: tesseraql/v1
                id: products.adjust
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                  policy: inv.write
                input:
                  delta: { type: integer, required: true }
                validate:
                %s
                sql:
                  file: adjust.sql
                  mode: update
                response:
                  json:
                    body:
                      ok: "true"
                """.formatted(validate));
        Files.writeString(dir.resolve("web/products/adjust/adjust.sql"), "select 1\n");
        return dir;
    }

    @Test
    void aRouteLocalRuleRepeatingASharedOneIsFlagged(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "  sane:\n    rule: \"" + SANE + "\"\n    field: delta\n"));

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-FIELD-4613")
                && !finding.isError()
                && finding.message().contains("quantityStaysSane"));
    }

    @Test
    void aReferenceLintsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "  sane:\n    use: quantityStaysSane\n    field: delta\n"));

        assertThat(findings).noneMatch(finding -> finding.code().equals("TQL-FIELD-4613"));
        // Referenced, so the unreferenced-rule warning must stay quiet too.
        assertThat(findings).noneMatch(finding -> finding.code().equals("TQL-FIELD-4612"));
    }

    @Test
    void aGenuinelyDifferentLocalRuleIsNotFlagged(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "  positive:\n    rule: \"params.delta > 0\"\n    field: delta\n"));

        assertThat(findings).noneMatch(finding -> finding.code().equals("TQL-FIELD-4613"));
    }
}
