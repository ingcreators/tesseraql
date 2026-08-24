package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One issuer per stack (docs/token-issuance.md decision 9): when the stack file enables the
 * authorization server, every runtime in the stack validates the same RS256 key set, and the
 * validation block is <em>derived</em> here rather than declared per member — the issuer is the
 * stack origin, the JWKS is the surface's published document, and a member's audience is its
 * own address, which the address-is-the-name rule already fixed.
 */
final class StackIssuer {

    /**
     * TQL-OAUTH-3001: a JWT key source was declared where the stack's authorization server
     * already issues. The stack signs RS256 with the database-held key set, so a member's own
     * `secret`/`publicKey`/`jwksUri` — or one left in the stack file's `security.jwt` block —
     * would stand up a second issuer beside it; remove the key source and let the stack's be
     * the one.
     */
    private static final TqlErrorCode SECOND_ISSUER = new TqlErrorCode(TqlDomain.OAUTH, 3001);

    /**
     * TQL-OAUTH-3002: the authorization server is enabled but the stack declares no external
     * origin. The issuer IS the origin — metadata, JWKS addresses and every member's derived
     * audience hang off it — so guessing one would misname the issuer everywhere at once;
     * declare `externalOrigin` in tesseraql-stack.yml.
     */
    private static final TqlErrorCode NO_ORIGIN = new TqlErrorCode(TqlDomain.OAUTH, 3002);

    /** The published key set, relative to the stack origin. */
    static final String JWKS_PATH = "/_tesseraql/oauth/jwks";

    private StackIssuer() {
    }

    /** Whether the stack file turns the authorization server on. */
    static boolean enabled(Map<String, Object> security) {
        if (security == null) {
            return false;
        }
        Object oauth = security.get("oauth");
        return oauth instanceof Map<?, ?> map
                && Boolean.parseBoolean(String.valueOf(map.get("enabled")));
    }

    /** The origin the issuer needs, or the refusal naming the stack file. */
    static String requireOrigin(String externalOrigin) {
        if (externalOrigin == null || externalOrigin.isBlank()) {
            throw new TqlException(NO_ORIGIN, "security.oauth.enabled is true but the stack"
                    + " declares no externalOrigin — the issuer is the origin, so declare"
                    + " externalOrigin in tesseraql-stack.yml");
        }
        return externalOrigin;
    }

    /**
     * The validation block every runtime in the stack shares: RS256 against the surface's
     * published JWKS, the origin as issuer, and the claim names the stack's mints write —
     * the stack file's `security.jwt` names when it declares them, the stack's defaults
     * otherwise. The audience is deliberately absent: it is per runtime, derived from the
     * address in {@link #apply}.
     */
    static Map<String, Object> jwt(String externalOrigin, Map<String, Object> security) {
        String origin = requireOrigin(externalOrigin);
        Map<String, Object> declared = security != null
                && security.get("jwt") instanceof Map<?, ?> map
                        ? castClaims(map)
                        : Map.of();
        Map<String, Object> jwt = new LinkedHashMap<>();
        jwt.put("algorithm", "RS256");
        jwt.put("jwksUri", origin + JWKS_PATH);
        jwt.put("issuer", origin);
        jwt.put("rolesClaim", declared.getOrDefault("rolesClaim", "roles"));
        jwt.put("permissionsClaim", declared.getOrDefault("permissionsClaim", "permissions"));
        for (String claim : List.of("groupsClaim", "tenantClaim", "loginClaim", "nameClaim",
                "clockSkew")) {
            Object value = declared.get(claim);
            if (value != null) {
                jwt.put(claim, value);
            }
        }
        return jwt;
    }

    /**
     * Applies the stack's validation block to one runtime's configuration: refuses a declared
     * key source first, merges the derived block over `tesseraql.security.jwt`, and defaults
     * the audience to this runtime's own address — {@code origin + basePath}, the resource
     * identifier the address rule already gives every member — when nothing declares one.
     */
    static AppConfig apply(AppConfig config, Map<String, Object> jwt, String externalOrigin,
            String basePath, String describedAs) {
        for (String keySource : List.of("secret", "publicKey", "jwksUri")) {
            if (config.getString("tesseraql.security.jwt." + keySource).isPresent()) {
                throw new TqlException(SECOND_ISSUER, "The stack's authorization server issues"
                        + " for this stack, but " + describedAs + " declares"
                        + " tesseraql.security.jwt." + keySource + " — a second issuer beside"
                        + " it; remove the key source (the stack file's security.jwt block"
                        + " keeps only claim names and audience)");
            }
        }
        Map<String, Object> root = SystemApps.deepCopy(config.root());
        Map<String, Object> tesseraql = SystemApps.childMap(root, "tesseraql");
        Map<String, Object> security = SystemApps.childMap(tesseraql, "security");
        Map<String, Object> block = SystemApps.childMap(security, "jwt");
        Object declared = block.get("audience");
        block.putAll(jwt);
        block.put("audience", audiences(declared, externalOrigin, basePath,
                config.getString("tesseraql.mcp.resource").orElse(null)));
        return new AppConfig(root);
    }

    /**
     * A runtime's accepted audiences under the stack issuer: anything it declared, plus its
     * own address, plus the stack origin, plus its own MCP resource — the last three always,
     * because they are the issuer's vocabulary rather than the application's. The address is
     * how the stack's mints name one member (the OAuth grants' per-member boundary — a token
     * granted for one member refuses at the next); the origin is the exchange's stack-wide
     * mint, a bearer with the reach the session already has (docs/stack-architecture.md
     * decision 27); the MCP resource is the address's `/_tesseraql/mcp` subordinate, the name
     * an OAuth grant for the member's MCP surface carries (RFC 8707). A tool call re-runs the
     * route's own bearer validation with that token, so the member must accept the narrower
     * name of itself — refusing it left every AS-minted MCP token passing the transport gate
     * and failing every tool ({@code TQL-SEC-4143}), the 2026-08-24 Codex acceptance's second
     * finding. It widens nothing: the MCP surface executes member routes with that token
     * anyway, and it stays one member's name — the per-member boundary is untouched.
     */
    private static List<String> audiences(Object declared, String externalOrigin,
            String basePath, String declaredMcpResource) {
        java.util.LinkedHashSet<String> audience = new java.util.LinkedHashSet<>();
        if (declared instanceof List<?> list) {
            list.forEach(value -> audience.add(String.valueOf(value)));
        } else if (declared != null) {
            audience.add(String.valueOf(declared));
        }
        String address = externalOrigin + (basePath == null ? "" : basePath);
        audience.add(address);
        audience.add(externalOrigin);
        // The same derivation the MCP transport gate uses, override included.
        audience.add(declaredMcpResource != null
                ? declaredMcpResource
                : address + "/_tesseraql/mcp");
        return List.copyOf(audience);
    }

    private static Map<String, Object> castClaims(Map<?, ?> map) {
        Map<String, Object> claims = new LinkedHashMap<>();
        map.forEach((key, value) -> claims.put(String.valueOf(key), value));
        return claims;
    }
}
