package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** A resolved scope: a predicate sub-template and the bind values it evaluates against. */
    record Resolved(List<SqlNode> nodes, Map<String, Object> bindings) {
        public Resolved {
            nodes = List.copyOf(nodes);
            // Bind values may legitimately be null (e.g. an absent principal claim), so this cannot
            // use Map.copyOf, which rejects null values.
            bindings = bindings == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        }
    }
}
