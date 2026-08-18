package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The namespace fence for an application's own permission codes (docs/stack-shells.md structural
 * decision 1, {@code TQL-YAML-1406}): every code the application's policies reference begins with
 * the application's own name — so two applications cannot silently share one grant — and never
 * with the framework's {@code tql.} mark.
 */
class AppLinterPolicyCodeTest {

    private static void write(Path dir, String policies) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: orders
                  security:
                    policies:
                """ + policies);
    }

    private static List<LintFinding> fence(List<LintFinding> findings) {
        return findings.stream().filter(finding -> "TQL-YAML-1406".equals(finding.code()))
                .toList();
    }

    @Test
    void aCodeCarryingTheApplicationsOwnNamePasses(@TempDir Path dir) throws Exception {
        write(dir, """
                      orders.read:
                        anyOf:
                          - role: READER
                          - permission: orders.approve
                """);
        assertThat(fence(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aBareCodeIsRefused(@TempDir Path dir) throws Exception {
        write(dir, """
                      orders.read:
                        anyOf:
                          - permission: approve
                """);
        List<LintFinding> findings = fence(new AppLinter().lint(dir));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(LintFinding.Severity.ERROR);
        assertThat(findings.get(0).message()).contains("approve").contains("orders.<what>");
    }

    @Test
    void aCodeUnderTheFrameworksMarkIsRefused(@TempDir Path dir) throws Exception {
        // tql.* is the framework's atom vocabulary: granted to principals, never re-declared
        // as an application's own code — the fence names the mark.
        write(dir, """
                      orders.read:
                        anyOf:
                          - permission: tql.ops.view.orders
                """);
        List<LintFinding> findings = fence(new AppLinter().lint(dir));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("framework's own mark");
    }

    /** Roles stay the deployment's vocabulary: a role rule is never fenced. */
    @Test
    void roleRulesAreNotFenced(@TempDir Path dir) throws Exception {
        write(dir, """
                      orders.read:
                        anyOf:
                          - role: tql.anything.goes
                """);
        assertThat(fence(new AppLinter().lint(dir))).isEmpty();
    }

    /** Policy ids are local to the configuration and never reach the store: not fenced. */
    @Test
    void policyIdsAreNotFenced(@TempDir Path dir) throws Exception {
        write(dir, """
                      anything.at.all:
                        anyOf:
                          - role: READER
                """);
        assertThat(fence(new AppLinter().lint(dir))).isEmpty();
    }
}
