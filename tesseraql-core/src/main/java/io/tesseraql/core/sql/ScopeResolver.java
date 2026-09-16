package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.OrderedCopies;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@code /*%scope name on alias *}{@code /} directive into a parameterized SQL predicate
 * derived from the request principal (roadmap Phase 29 — organizational data scoping).
 *
 * <p>The {@link SqlRenderer} owns no knowledge of principals, authorization policies, or scope
 * definitions — those live in modules above {@code tesseraql-core}. When the renderer meets a
 * {@link SqlNode.Scope} node it delegates to this SPI, which decides (from the principal) which
 * predicate applies and returns it as a sub-template plus the bind values that sub-template renders
 * against. The renderer splices both into the surrounding statement, so the scoped column filter is
 * always a {@code ?} placeholder, never string-concatenated SQL.
 */
public interface ScopeResolver {

    /** TQL-SQL-2106: a scope directive was rendered without a resolver configured. */
    TqlErrorCode UNSUPPORTED_CODE = new TqlErrorCode(TqlDomain.SQL, 2106);

    /**
     * The default for render paths that never carry a scope directive: it rejects any scope node, so
     * an accidental {@code /*%scope%/} fails loudly rather than silently bypassing data scoping.
     */
    ScopeResolver UNSUPPORTED = (scopeName, alias, context) -> {
        throw new TqlException(UNSUPPORTED_CODE,
                "No scope resolver is configured to expand /*%scope " + scopeName + " */");
    };

    /**
     * Resolves the named scope for the request context.
     *
     * @param scopeName the scope id named by the directive
     * @param alias     the table alias supplied by {@code on <alias>}, or {@code null}
     * @param context   the request execution context (it carries {@code principal})
     * @return the predicate sub-template and the bind values it renders against
     */
    Resolved resolve(String scopeName, String alias, Map<String, Object> context);

    /**
     * A resolved scope: a predicate sub-template and the bind values it evaluates against — or,
     * when several arms matched, one {@link Fragment} per arm, each with its own binds
     * (docs/data-scoping.md "Composition"). The renderer OR-combines the fragments and layers
     * each fragment's binds around that fragment alone, so two arms that name the same bind
     * ({@code /* units *}{@code /} in a staff arm and a manager arm) each render against their
     * own values. One map for every fragment used to let the last matching arm's value win the
     * name, and the other arm's rows vanished — silently, in arm order
     * (docs/audit-low-leads.md G27).
     *
     * @param nodes     the single sub-template, or the whole OR-combination for a resolver that
     *                  composes it itself (unused by the renderer when {@code fragments} is set)
     * @param bindings  the binds of the single sub-template
     * @param fragments the per-arm fragments, empty for the single-template shape
     */
    record Resolved(List<SqlNode> nodes, Map<String, Object> bindings,
            List<Fragment> fragments) {

        /** The single-template shape: one predicate, one bind map. */
        public Resolved(List<SqlNode> nodes, Map<String, Object> bindings) {
            this(nodes, bindings, List.of());
        }

        public Resolved {
            nodes = List.copyOf(nodes);
            // Bind values may legitimately be null (e.g. an absent principal claim), so this
            // names the null-permitting copy rather than OrderedCopies.map.
            bindings = bindings == null ? Map.of() : OrderedCopies.mapAllowingNulls(bindings);
            fragments = fragments == null ? List.of() : List.copyOf(fragments);
        }

        /** The OR-combination of {@code fragments}, each rendered against its own binds. */
        public static Resolved of(List<Fragment> fragments) {
            return new Resolved(List.of(), Map.of(), fragments);
        }
    }

    /** One matching arm's predicate and the binds it — and only it — evaluates against. */
    record Fragment(List<SqlNode> nodes, Map<String, Object> bindings) {
        public Fragment {
            nodes = List.copyOf(nodes);
            bindings = bindings == null ? Map.of() : OrderedCopies.mapAllowingNulls(bindings);
        }
    }
}
