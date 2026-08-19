package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minting a bearer token from a principal the caller already is
 * (docs/session-token-exchange.md), and the two service providers the console page renders
 * (docs/stack-architecture.md Decision 20).
 *
 * <p>One signer for both faces. {@link TokenExchangeRouteBuilder} is the JSON endpoint a script
 * calls; {@code ops.token.issue} is the same mint behind a page, so a token copied out of the
 * console and a token fetched by {@code tesseraql token --url} carry identical claims. Two
 * signers would have drifted the first time a claim was added to one of them.
 */
final class SessionTokens {

    private static final System.Logger LOG = System.getLogger(SessionTokens.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtConfig jwt;
    private final Duration lifetime;
    private final String ttl;
    private final boolean enabled;

    /**
     * @param ttl the lifetime as the operator wrote it ({@code 15m}), for display; the parsed
     *            {@code lifetime} is what expiry is computed from
     */
    SessionTokens(JwtConfig jwt, Duration lifetime, String ttl, boolean enabled) {
        this.jwt = jwt;
        this.lifetime = lifetime;
        this.ttl = ttl;
        this.enabled = enabled;
    }

    /** Whether this application issues, and for how long — what the console page asks first. */
    Map<String, Object> status() {
        return Map.of("enabled", enabled, "ttl", ttl);
    }

    /**
     * The {@code ops.token.issue} provider: mints for the caller's own principal.
     *
     * <p>The principal is the <b>ambient</b> one (docs/ambient-params.md) — the curated map the
     * request binder seeds from the authenticated exchange, not something the calling route
     * chose. That is what stops this provider from being a mint-anything-for-anyone hole: a route
     * that wired {@code subject: 'admin'} would be writing a parameter this method never reads.
     * Display name is the one field outside the ambient set, so the console route passes it
     * explicitly and a console-issued token carries the same name claim the endpoint's does.
     * The acting-role narrowing (docs/application-roles.md) rides the same discipline: the
     * console route passes {@code principal.roleGrants}/{@code principal.directPermissions} —
     * resolved from the authenticated exchange, never caller-writable — and the mint selects
     * from them, so a forged {@code actingRole} can only narrow to a role the caller holds.
     */
    Map<String, Object> issue(Map<String, Object> params) {
        if (!enabled) {
            // Not an error: an application that does not issue tokens answering "I do not issue
            // tokens" is the correct answer, and the page renders it as the config key to set
            // rather than as a failure the operator has to decode from a 500.
            return status();
        }
        Object ambient = params.get("principal");
        if (!(ambient instanceof Map<?, ?> caller)) {
            throw new IllegalStateException(
                    "ops.token.issue reached without an authenticated principal");
        }
        Principal principal = new Principal(
                string(caller.get("subject")), string(caller.get("loginId")),
                string(params.get("displayName")), string(caller.get("tenantId")),
                strings(caller.get("groups")), strings(caller.get("roles")),
                strings(caller.get("permissions")), Map.of(),
                grants(params.get("roleGrants")), strings(params.get("directPermissions")));
        String acting = string(params.get("actingRole"));
        if (acting != null && !acting.isBlank()) {
            principal = activated(principal, acting);
        }
        Map<String, Object> minted = new LinkedHashMap<>(status());
        minted.putAll(mint(principal));
        return minted;
    }

    /**
     * The active view for one selected role (the token face of activation): the mint carries
     * the narrowed roles and permissions plus the {@code acting_role} claim, refused with
     * TQL-SEC-4148 when the caller does not hold the role — including a bearer whose claims
     * assert roles but whose store attribution is empty.
     */
    static Principal activated(Principal principal, String acting) {
        io.tesseraql.security.Principal.RoleGrant chosen = principal.roleGrants().stream()
                .filter(grant -> acting.equals(grant.role()) && grant.application() != null)
                .findFirst()
                .orElseThrow(() -> new io.tesseraql.core.error.TqlException(
                        io.tesseraql.security.Activation.WRONG_CAPACITY,
                        "The caller does not hold application role '" + acting
                                + "', so a token cannot be minted acting as it"));
        return io.tesseraql.security.Activation.activate(principal, chosen.application(),
                acting);
    }

    /** The endpoint's answer body: the token, its type, and when it stops working. */
    Map<String, Object> mint(Principal principal) {
        Instant expiry = Instant.now().plus(lifetime);
        String minted;
        try {
            minted = sign(principal, expiry);
        } catch (java.security.GeneralSecurityException
                | com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("Could not sign a bearer token", ex);
        }
        // Recorded because a token outliving the session that produced it is a credential nobody
        // would otherwise know exists.
        LOG.log(System.Logger.Level.INFO,
                "Issued a bearer token for subject {0}, expiring {1}", principal.subject(), expiry);
        return Map.of("token", minted, "tokenType", "Bearer", "expiresAt", expiry.toString());
    }

    /** The claims the bearer path reads, signed with the secret it verifies against. */
    private String sign(Principal principal, Instant expiry)
            throws java.security.GeneralSecurityException,
            com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", principal.subject());
        put(payload, jwt.loginClaim(), principal.loginId());
        put(payload, jwt.nameClaim(), principal.displayName());
        put(payload, jwt.tenantClaim(), principal.tenantId());
        if (!principal.roles().isEmpty()) {
            payload.put(jwt.rolesClaim(), principal.roles());
        }
        if (!principal.permissions().isEmpty()) {
            payload.put(jwt.permissionsClaim(), principal.permissions());
        }
        if (!principal.groups().isEmpty()) {
            payload.put(jwt.groupsClaim(), principal.groups());
        }
        // The capacity claim (docs/application-roles.md): a token minted --as carries the
        // active view above and says so, so the member's audit writes the same sentence for
        // a machine caller as for a tab.
        Object acting = principal.claims()
                .get(io.tesseraql.security.Activation.ACTING_ROLE_CLAIM);
        if (acting != null) {
            payload.put("acting_role", String.valueOf(acting));
        }
        if (jwt.issuer() != null && !jwt.issuer().isBlank()) {
            payload.put("iss", jwt.issuer());
        }
        // The audience is required now (docs/audit-hardening.md Decision 1), so a token minted
        // without it would be signed correctly and refused on arrival by this same application.
        payload.put("aud", jwt.audience().size() == 1 ? jwt.audience().get(0) : jwt.audience());
        payload.put("exp", expiry.getEpochSecond());

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = encoder.encodeToString(MAPPER.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return header + "." + body + "." + encoder.encodeToString(
                mac.doFinal((header + "." + body).getBytes(StandardCharsets.US_ASCII)));
    }

    private static void put(Map<String, Object> payload, String claim, String value) {
        if (claim != null && value != null && !value.isBlank()) {
            payload.put(claim, value);
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static List<Principal.RoleGrant> grants(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Principal.RoleGrant> grants = new java.util.ArrayList<>();
        for (Object element : list) {
            if (element instanceof Principal.RoleGrant grant) {
                grants.add(grant);
            }
        }
        return grants;
    }
}
