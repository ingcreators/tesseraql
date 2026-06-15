package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lint rules for organizational data scoping (roadmap Phase 29, {@code TQL-SCOPE-30xx}). */
class AppLinterScopeTest {

    private static void writeRoute(Path dir, String sql) throws Exception {
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/list.sql"), sql);
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                sql:
                  file: list.sql
                """);
    }

    private static void writeScope(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("scope"));
        Files.writeString(dir.resolve("scope/by_region.sql"), "$.region in /* regions */ ('R1')\n");
        Files.writeString(dir.resolve("scope/orders_scope.yml"), """
                version: tesseraql/v1
                id: orders_scope
                kind: scope
                match:
                  - when: { role: org-admin }
                    apply: all
                  - when: { role: region-manager }
                    file: by_region.sql
                    params:
                      regions: principal.claim.regions
                """);
    }

    private static List<String> scopeCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter(c -> c.startsWith("TQL-SCOPE"))
                .toList();
    }

    @Test
    void wellFormedScopeAndDirectiveProduceNoScopeFindings(@TempDir Path dir) throws Exception {
        writeScope(dir);
        writeRoute(dir, "select * from orders o where /*%scope orders_scope on o */ (1=1)\n");
        assertThat(scopeCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void unknownScopeReferenceIsAnError(@TempDir Path dir) throws Exception {
        writeScope(dir);
        writeRoute(dir, "select * from orders o where /*%scope ghost on o */ (1=1)\n");
        assertThat(scopeCodes(new AppLinter().lint(dir))).contains("TQL-SCOPE-3011");
    }

    @Test
    void badAliasIsAnError(@TempDir Path dir) throws Exception {
        writeScope(dir);
        writeRoute(dir, "select * from orders o where /*%scope orders_scope on 9bad */ (1=1)\n");
        assertThat(scopeCodes(new AppLinter().lint(dir))).contains("TQL-SCOPE-3013");
    }

    @Test
    void invalidOrgUnitModeIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  orgunit:
                    mode: bogus
                """);
        assertThat(new AppLinter().lint(dir).stream().map(LintFinding::code).toList())
                .contains("TQL-SCOPE-3020");
    }

    @Test
    void malformedArmIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("scope"));
        // An arm declaring neither apply nor file is malformed.
        Files.writeString(dir.resolve("scope/broken.yml"), """
                version: tesseraql/v1
                id: broken
                kind: scope
                match:
                  - when: { role: org-admin }
                """);
        assertThat(scopeCodes(new AppLinter().lint(dir))).contains("TQL-SCOPE-3012");
    }
}
