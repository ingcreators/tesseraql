package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.SecuritySpec;
import java.util.Map;

/**
 * The floor under MCP primitives that declare no {@code security:} of their own
 * (docs/audit-hardening.md Decision 2, open question 4).
 *
 * <p>MCP documents never reach {@code applySecurityDefaults}: {@code ManifestLoader} loads them into
 * their own collections, and the path rules in {@code tesseraql.security.defaults.routes} match on a
 * served URL path. An MCP primitive has no URL path — it is reached by name over one shared
 * endpoint — so widening the path rules to cover it would leave "what does this rule match?" with
 * no answer. It gets its own block instead.
 *
 * <p>A write tool has had a floor all along: {@code TQL-MCP-4030} refuses one with no policy,
 * because an agent must not mutate data without authorization. A <em>read</em> primitive had none,
 * and that is what an intranet deployment actually needs closed — "anyone who can reach the port"
 * is a smaller blast radius than "anyone", not a zero one.
 *
 * <p>One block for every MCP document rather than a set narrowable by {@code kind:}. Narrowing is
 * additive if it is ever wanted; starting with it would be inventing a dimension before anybody has
 * asked for one.
 */
public final class McpSecurityDefaults {

    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.SEC, 4145);

    private final String auth;
    private final String policy;

    private McpSecurityDefaults(String auth, String policy) {
        this.auth = auth;
        this.policy = policy;
    }

    /** Parses {@code tesseraql.security.defaults.mcp}; absent config yields no floor. */
    public static McpSecurityDefaults from(AppConfig config) {
        Object node = config.navigate("tesseraql.security.defaults.mcp");
        if (node == null) {
            return new McpSecurityDefaults(null, null);
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new TqlException(INVALID,
                    "tesseraql.security.defaults.mcp must be a map of auth/policy");
        }
        String auth = string(map.get("auth"));
        String policy = string(map.get("policy"));
        if (auth == null && policy == null) {
            throw new TqlException(INVALID,
                    "tesseraql.security.defaults.mcp declares nothing to default (auth or policy)");
        }
        return new McpSecurityDefaults(auth, policy);
    }

    /** Whether any floor is declared — lets callers skip per-document work entirely. */
    public boolean isEmpty() {
        return auth == null && policy == null;
    }

    /**
     * Resolves one primitive's effective security: what the document declares wins, the block fills
     * the rest.
     *
     * <p>{@code public} is treated exactly as the route defaults treat it — a floor must not
     * quietly attach a policy to a primitive declared open, because that would authorize an
     * anonymous principal. The linter reports the combination instead of the resolver papering
     * over it.
     */
    public SecuritySpec resolve(SecuritySpec declared) {
        if (isEmpty()) {
            return declared;
        }
        String effectiveAuth = declared != null && declared.auth() != null
                ? declared.auth()
                : auth;
        String effectivePolicy = declared != null && declared.policy() != null
                ? declared.policy()
                : "public".equals(effectiveAuth) ? null : policy;
        if (effectiveAuth == null && effectivePolicy == null) {
            return declared;
        }
        // csrf is carried through unchanged: an MCP call is not a browser form post, and the
        // enum has no meaning on this surface.
        return new SecuritySpec(effectiveAuth, effectivePolicy,
                declared == null ? null : declared.csrf());
    }

    private static String string(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value)
                        .trim();
    }
}
