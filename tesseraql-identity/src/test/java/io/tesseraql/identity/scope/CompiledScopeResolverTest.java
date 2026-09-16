package io.tesseraql.identity.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.model.MatchArm;
import io.tesseraql.yaml.model.ScopeDefinition;
import io.tesseraql.yaml.model.WhenCondition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Role-conditional, additive scope resolution (roadmap Phase 29). */
class CompiledScopeResolverTest {

    @TempDir
    Path dir;

    private CompiledScopeResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("by_region.sql"), "$.region in /* regions */ ('R1')");
        Files.writeString(dir.resolve("own_rows.sql"), "$.created_by = /* uid */ 'u'");
        ScopeDefinition definition = new ScopeDefinition("tesseraql/v1", "orders_scope", "scope",
                List.of(
                        new MatchArm(when("role", "org-admin"), "all", null, Map.of()),
                        new MatchArm(when("role", "region-manager"), null, "by_region.sql",
                                Map.of("regions", "principal.claim.regions")),
                        new MatchArm(when("permission", "orders:read-own"), null, "own_rows.sql",
                                Map.of("uid", "principal.subject")),
                        // A second arm over the same fragment and the same bind name, from a
                        // different claim: the shared-name shape the shipped procurement scope
                        // has (docs/audit-low-leads.md G27).
                        new MatchArm(when("role", "auditor"), null, "by_region.sql",
                                Map.of("regions", "principal.claim.auditRegions"))));
        ScopeFile scopeFile = new ScopeFile(dir.resolve("orders_scope.yml"), definition);
        resolver = new CompiledScopeResolver(List.of(scopeFile), "");
    }

    private static WhenCondition when(String kind, String value) {
        return switch (kind) {
            case "role" -> new WhenCondition(value, null, null, null);
            case "permission" -> new WhenCondition(null, value, null, null);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static Principal principal(List<String> roles, List<String> permissions,
            Map<String, Object> claims) {
        return new Principal("u9", "login", "Name", null, List.of(), roles, permissions, claims);
    }

    /** Renders through the directive, so the renderer's per-fragment layering is under test. */
    private BoundSql resolve(String alias, Principal principal) {
        Map<String, Object> context = principal == null
                ? Map.of()
                : Map.of("principal", principal);
        String directive = alias == null
                ? "/*%scope orders_scope */ (1=1)"
                : "/*%scope orders_scope on " + alias + " */ (1=1)";
        return SqlRenderer.render(io.tesseraql.core.sql.Sql2WayParser.parse(directive), Map.of(),
                resolver, context);
    }

    @Test
    void bypassRoleSeesEverything() {
        BoundSql bound = resolve("o", principal(List.of("org-admin"), List.of(), Map.of()));
        assertThat(bound.sql()).isEqualTo("(1=1)");
        assertThat(bound.parameters()).isEmpty();
    }

    @Test
    void managerSeesTheirRegionsQualifiedByAlias() {
        BoundSql bound = resolve("o",
                principal(List.of("region-manager"), List.of(), Map.of("regions",
                        List.of("R1", "R2"))));
        assertThat(bound.sql()).contains("o.region in (?, ?)");
        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("R1", "R2");
    }

    @Test
    void repSeesOnlyOwnRows() {
        BoundSql bound = resolve("o", principal(List.of(), List.of("orders:read-own"), Map.of()));
        assertThat(bound.sql()).contains("o.created_by = ?");
        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly("u9");
    }

    @Test
    void matchingArmsComposeAdditivelyWithOr() {
        BoundSql bound = resolve("o", principal(List.of("region-manager"),
                List.of("orders:read-own"), Map.of("regions", List.of("R1"))));
        assertThat(bound.sql()).contains("o.region in (?)").contains(" or ")
                .contains("o.created_by = ?");
        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("R1", "u9");
    }

    /**
     * Two matching arms that name the same bind each render against their own values: the
     * staff arm's regions and the auditor's, both present, in arm order. One bind map for the
     * whole OR let the last arm's value win the name and the other arm's rows vanished.
     */
    @Test
    void matchingArmsSharingABindNameEachKeepTheirOwnValues() {
        BoundSql bound = resolve("o", principal(List.of("region-manager", "auditor"), List.of(),
                Map.of("regions", List.of("M1", "M2"), "auditRegions", List.of("A1"))));
        assertThat(bound.sql()).isEqualTo("((o.region in (?, ?)) or (o.region in (?)))");
        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("M1", "M2", "A1");
    }

    @Test
    void noMatchingArmDeniesByDefault() {
        BoundSql bound = resolve("o", principal(List.of(), List.of(), Map.of()));
        assertThat(bound.sql()).isEqualTo("(1=0)");
    }

    @Test
    void absentPrincipalDeniesByDefault() {
        BoundSql bound = resolve("o", null);
        assertThat(bound.sql()).isEqualTo("(1=0)");
    }

    @Test
    void presentButEmptyWhenFailsAtConstructionRatherThanMatchingEveryone() {
        ScopeDefinition definition = new ScopeDefinition("tesseraql/v1", "leaky_scope", "scope",
                List.of(new MatchArm(new WhenCondition(null, null, null, null), "all", null,
                        Map.of())));
        ScopeFile scopeFile = new ScopeFile(dir.resolve("leaky_scope.yml"), definition);
        assertThatThrownBy(() -> new CompiledScopeResolver(List.of(scopeFile), ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("declares no role");
    }

    @Test
    void omittedWhenStaysAnUnconditionalArm() {
        ScopeDefinition definition = new ScopeDefinition("tesseraql/v1", "catch_all", "scope",
                List.of(new MatchArm(null, "all", null, Map.of())));
        ScopeFile scopeFile = new ScopeFile(dir.resolve("catch_all.yml"), definition);
        CompiledScopeResolver catchAll = new CompiledScopeResolver(List.of(scopeFile), "");
        ScopeResolver.Resolved resolved = catchAll.resolve("catch_all", "o",
                Map.of("principal", principal(List.of(), List.of(), Map.of())));
        assertThat(SqlRenderer.render(resolved.nodes(), resolved.bindings()).sql())
                .isEqualTo("(1=1)");
    }

    @Test
    void omittedAliasLeavesColumnUnqualified() {
        BoundSql bound = resolve(null,
                principal(List.of("region-manager"), List.of(), Map.of("regions", List.of("R1"))));
        assertThat(bound.sql()).contains("region in (?)").doesNotContain(".region");
    }
}
