package io.tesseraql.compiler.binding;

import io.tesseraql.core.dialect.DialectSqlResolver;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.Policy;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.model.MatchArm;
import io.tesseraql.yaml.model.ScopeDefinition;
import io.tesseraql.yaml.model.WhenCondition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime {@link ScopeResolver} (roadmap Phase 29 — organizational data scoping): expands a
 * {@code /*%scope name on alias *}{@code /} directive into a parameterized predicate derived from the
 * request principal.
 *
 * <p>Each scope's match arms are evaluated against the principal with the same role/permission/claim
 * matcher route policies use ({@link Policy.Rule}). The matching arms compose <em>additively</em>: a
 * principal sees a row if any matching arm would, so the arms' fragments are OR-combined. An
 * {@code apply: all} arm short-circuits to {@code (1=1)}; matching no arm is deny-by-default
 * ({@code (1=0)}). The scoped column is qualified with the directive's {@code on <alias>} (the
 * fragment's {@code $} sentinel), and the fragment's value binds stay {@code ?} placeholders.
 *
 * <p>Fragment files are parsed once at construction so a missing or malformed fragment fails at
 * startup, not on first request.
 */
public final class CompiledScopeResolver implements ScopeResolver {

    /** TQL-SQL-2107: a scope directive named a scope not declared under {@code scope/}. */
    private static final TqlErrorCode UNKNOWN_SCOPE = new TqlErrorCode(TqlDomain.SQL, 2107);

    private final Map<String, List<CompiledArm>> scopes;

    public CompiledScopeResolver(List<ScopeFile> scopeFiles, String dialect) {
        Map<String, List<CompiledArm>> compiled = new LinkedHashMap<>();
        for (ScopeFile scopeFile : scopeFiles) {
            ScopeDefinition definition = scopeFile.definition();
            Path scopeDir = scopeFile.source().getParent();
            List<CompiledArm> arms = new ArrayList<>();
            for (MatchArm arm : definition.match()) {
                arms.add(compileArm(arm, scopeDir, dialect));
            }
            compiled.put(definition.id(), arms);
        }
        this.scopes = compiled;
    }

    private static CompiledArm compileArm(MatchArm arm, Path scopeDir, String dialect) {
        List<SqlNode> fragment = null;
        if (arm.file() != null && !arm.file().isBlank()) {
            Path file = DialectSqlResolver.resolve(scopeDir.resolve(arm.file()).normalize(),
                    dialect);
            try {
                fragment = Sql2WayParser.parse(Files.readString(file));
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to read scope fragment " + file, ex);
            }
        }
        return new CompiledArm(rule(arm.when()), arm.isAll(), arm.isNone(), fragment, arm.params());
    }

    /** Maps a {@code when:} condition to a policy rule; {@code null} means an unconditional arm. */
    private static Policy.Rule rule(WhenCondition when) {
        if (when == null) {
            return null;
        }
        if (when.role() != null) {
            return Policy.Rule.ofRole(when.role());
        }
        if (when.permission() != null) {
            return Policy.Rule.ofPermission(when.permission());
        }
        if (when.claim() != null) {
            return Policy.Rule.ofClaim(when.claim(), when.value());
        }
        return null;
    }

    @Override
    public Resolved resolve(String scopeName, String alias, Map<String, Object> context) {
        List<CompiledArm> arms = scopes.get(scopeName);
        if (arms == null) {
            throw new TqlException(UNKNOWN_SCOPE,
                    "Scope '" + scopeName + "' is not declared under scope/");
        }
        Map<String, Object> ctx = context == null ? Map.of() : context;
        Principal principal = ctx.get("principal") instanceof Principal p ? p : null;
        EvaluationContext evaluation = new EvaluationContext(ctx);

        List<List<SqlNode>> predicates = new ArrayList<>();
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (CompiledArm arm : arms) {
            if (!arm.matches(principal)) {
                continue;
            }
            if (arm.all()) {
                return new Resolved(List.of(text("(1=1)")), Map.of());
            }
            if (arm.fragment() == null) {
                continue; // a matching `apply: none` arm contributes nothing to the OR
            }
            predicates.add(substituteAlias(arm.fragment(), alias));
            arm.params().forEach((bind, expr) -> bindings.put(bind,
                    evaluation.resolve(Arrays.asList(expr.split("\\.")))));
        }
        if (predicates.isEmpty()) {
            return new Resolved(List.of(text("(1=0)")), Map.of()); // deny by default
        }
        return new Resolved(combine(predicates), bindings);
    }

    /** OR-combines the matching fragments into {@code ((frag1) or (frag2) ...)}. */
    private static List<SqlNode> combine(List<List<SqlNode>> predicates) {
        List<SqlNode> out = new ArrayList<>();
        out.add(text("("));
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                out.add(text(" or "));
            }
            out.add(text("("));
            out.addAll(predicates.get(i));
            out.add(text(")"));
        }
        out.add(text(")"));
        return out;
    }

    /** Replaces the {@code $} scope-target sentinel with the call site's {@code on <alias>} prefix. */
    private static List<SqlNode> substituteAlias(List<SqlNode> nodes, String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        List<SqlNode> out = new ArrayList<>(nodes.size());
        for (SqlNode node : nodes) {
            if (node instanceof SqlNode.Text textNode) {
                out.add(new SqlNode.Text(textNode.text().replace("$.", prefix),
                        textNode.startLine()));
            } else {
                out.add(node);
            }
        }
        return out;
    }

    private static SqlNode.Text text(String sql) {
        return new SqlNode.Text(sql, 0);
    }

    private record CompiledArm(Policy.Rule rule, boolean all, boolean none, List<SqlNode> fragment,
            Map<String, String> params) {

        boolean matches(Principal principal) {
            return rule == null || (principal != null && rule.matches(principal));
        }
    }
}
