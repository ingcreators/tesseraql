package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A route policy that resolves its atom from the route's own path
 * (docs/access-governance.md structural decision 7, {@code TQL-YAML-1409}).
 *
 * <p>The compiler refuses the same three shapes at boot. This is the reading that carries a
 * file and a line, so the author sees where the gate cannot resolve rather than watching every
 * request to the route answer 403.
 */
class AppLinterPolicyTemplateTest {

    private static void write(Path dir, String routeDir, String policy) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: orders
                """);
        Files.createDirectories(dir.resolve(routeDir));
        Files.writeString(dir.resolve(routeDir).resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.detail
                kind: route
                recipe: query-json
                security:
                  auth: browser
                  policy: %s
                sources:
                  main:
                    sql:
                      file: read.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(policy));
        Files.writeString(dir.resolve(routeDir).resolve("read.sql"), "select 1 as one\n");
    }

    private static List<LintFinding> unresolvable(List<LintFinding> findings) {
        return findings.stream().filter(finding -> "TQL-YAML-1409".equals(finding.code()))
                .toList();
    }

    @Test
    void aTemplateNamingTheRoutesOwnPathParameterPasses(@TempDir Path dir) throws Exception {
        write(dir, "web/applications/{name}", "tql.iam.write.{path.name}");
        assertThat(unresolvable(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void anUndeclaredPathParameterIsAnError(@TempDir Path dir) throws Exception {
        write(dir, "web/applications/{name}", "tql.iam.write.{path.app}");
        List<LintFinding> findings = unresolvable(new AppLinter().lint(dir));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(LintFinding.Severity.ERROR);
        assertThat(findings.get(0).message()).contains("[name]").contains("resolve to nothing");
    }

    @Test
    void aQueryOrBodyReferenceIsAnError(@TempDir Path dir) throws Exception {
        write(dir, "web/applications/{name}", "tql.iam.write.{query.name}");
        assertThat(unresolvable(new AppLinter().lint(dir))).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains("own path"));
    }

    @Test
    void aTemplateOutsideTheFrameworkMarkIsAnError(@TempDir Path dir) throws Exception {
        write(dir, "web/applications/{name}", "orders.admin.{path.name}");
        assertThat(unresolvable(new AppLinter().lint(dir))).singleElement()
                .satisfies(finding -> assertThat(finding.message())
                        .contains("names no policy at all"));
    }

    /**
     * A framework atom id is defined by construction — it is the synthesized atom check, with
     * no declaration behind it — so referencing one is not an undefined policy. Reading it as
     * one warned every application that gated a route on a framework surface's atom.
     */
    @Test
    void aFrameworkAtomIdIsNotReportedAsUndefined(@TempDir Path dir) throws Exception {
        write(dir, "web/applications/{name}", "tql.iam.view.{path.name}");
        assertThat(new AppLinter().lint(dir))
                .noneMatch(finding -> "TQL-SEC-4030".equals(finding.code()));
    }
}
