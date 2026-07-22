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

    /** A write (command-json, update mode) on {@code orders}, at a distinct route path. */
    private static void writeCommandRoute(Path dir, String sql) throws Exception {
        Files.createDirectories(dir.resolve("web/orders/adjust"));
        Files.writeString(dir.resolve("web/orders/adjust/adjust.sql"), sql);
        Files.writeString(dir.resolve("web/orders/adjust/post.yml"), """
                version: tesseraql/v1
                id: orders.adjust
                kind: route
                recipe: command-json
                sql:
                  file: adjust.sql
                  mode: update
                response:
                  json:
                    status: 200
                """);
    }

    private static List<String> scopeCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter(c -> c.startsWith("TQL-SCOPE"))
                .toList();
    }

    private static List<String> writeScopeCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter("TQL-SEC-4100"::equals).toList();
    }

    @Test
    void unscopedWriteOnAScopeGovernedTableWarns(@TempDir Path dir) throws Exception {
        writeScope(dir);
        // The read scopes `orders`, marking it scope-governed for the app…
        writeRoute(dir, "select * from orders o where /*%scope orders_scope on o */ (1=1)\n");
        // …but this write on the same table carries no scope predicate.
        writeCommandRoute(dir,
                "update orders set status = /* status */ 'shipped' where id = /* id */ 1\n");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(writeScopeCodes(findings)).containsExactly("TQL-SEC-4100");
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4100")
                && "warning".equals(f.severity()) && f.message().contains("orders"));
    }

    @Test
    void scopedWriteOnAScopeGovernedTableIsClean(@TempDir Path dir) throws Exception {
        writeScope(dir);
        writeRoute(dir, "select * from orders o where /*%scope orders_scope on o */ (1=1)\n");
        writeCommandRoute(dir, "update orders o set status = /* status */ 'shipped'"
                + " where o.id = /* id */ 1 and /*%scope orders_scope on o */ (1=1)\n");
        assertThat(writeScopeCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void writeOnATableTheAppNeverScopesIsClean(@TempDir Path dir) throws Exception {
        writeScope(dir);
        writeRoute(dir, "select * from orders o where /*%scope orders_scope on o */ (1=1)\n");
        // A write on a different, ungoverned table draws no warning.
        writeCommandRoute(dir, "delete from audit_log where created_at < /* cutoff */ now()\n");
        assertThat(writeScopeCodes(new AppLinter().lint(dir))).isEmpty();
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
