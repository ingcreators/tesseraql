package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

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
                                Map.of("uid", "principal.subject"))));
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

    private BoundSql resolve(String alias, Principal principal) {
        ScopeResolver.Resolved resolved = resolver.resolve("orders_scope", alias,
                principal == null ? Map.of() : Map.of("principal", principal));
        return SqlRenderer.render(resolved.nodes(), resolved.bindings());
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
    void omittedAliasLeavesColumnUnqualified() {
        BoundSql bound = resolve(null,
                principal(List.of("region-manager"), List.of(), Map.of("regions", List.of("R1"))));
        assertThat(bound.sql()).contains("region in (?)").doesNotContain(".region");
    }
}
