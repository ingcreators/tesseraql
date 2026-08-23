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
 * <p>One signer for both faces. {@link TokenExchangeRoutes} is the JSON endpoint a script
 * calls; {@code ops.token.issue} is the same mint behind a page, so a token copied out of the
 * console and a token fetched by {@code tesseraql token --url} carry identical claims. Two
 * signers would have drifted the first time a claim was added to one of them.
 */
final class SessionTokens {

    private static final System.Logger LOG = System.getLogger(SessionTokens.class.getName());

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    private final JwtConfig jwt;
    private final Duration lifetime;
    private final String ttl;
    private final boolean enabled;
    /**
     * The stack's RS256 signer when the authorization server is enabled
     * (docs/token-issuance.md decision 9), looked up lazily because the extension installs
     * during the same start that wires this; {@code null} keeps the HS256 path.
     */
    private final java.util.function.Supplier<io.tesseraql.oauth.AccessTokenSigner> stackSigner;
    /**
     * The stack members a token may be minted for under the stack issuer — name to address —
     * and the origin their audiences derive from; both {@code null} away from the surface,
     * where the exchange keeps its single-application meaning.
     */
    private final Map<String, String> memberAddresses;
    private final String externalOrigin;

    /**
     * TQL-OAUTH-3003: a token request named a stack member this runtime does not address.
     * Either the name is not in the stack, or the exchange serving the request is not the
     * stack's surface — the member axis exists only where the member list does.
     */
    private static final io.tesseraql.core.error.TqlErrorCode UNKNOWN_MEMBER = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.OAUTH,
            3003);

    /**
     * @param ttl the lifetime as the operator wrote it ({@code 15m}), for display; the parsed
     *            {@code lifetime} is what expiry is computed from
     */
    SessionTokens(JwtConfig jwt, Duration lifetime, String ttl, boolean enabled) {
        this(jwt, lifetime, ttl, enabled, null, null, null);
    }

    SessionTokens(JwtConfig jwt, Duration lifetime, String ttl, boolean enabled,
            java.util.function.Supplier<io.tesseraql.oauth.AccessTokenSigner> stackSigner,
            Map<String, String> memberAddresses, String externalOrigin) {
        this.jwt = jwt;
        this.lifetime = lifetime;
        this.ttl = ttl;
        this.enabled = enabled;
        this.stackSigner = stackSigner;
        this.memberAddresses = memberAddresses;
        this.externalOrigin = externalOrigin;
    }

    /** Whether this application issues, for how long, and which members it can mint for. */
    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("ttl", ttl);
        status.put("applications", memberAddresses == null
                ? java.util.List.of()
                : java.util.List.copyOf(memberAddresses.keySet()));
        return status;
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
        String appName = string(params.get("appName"));
        principal = narrowed(principal, appName, acting);
        Map<String, Object> minted = new LinkedHashMap<>(status());
        minted.putAll(mint(principal, appName));
        return minted;
    }

    /**
     * The member axis of the unified issuer (docs/token-issuance.md decision 9): naming a
     * member narrows the mint to that member's active view — the browser's own entry rules,
     * per token: one held role auto-activates, several stay inactive unless {@code
     * actingRole} selects one, and a role stated for a member that does not grant it refuses
     * with TQL-SEC-4148. No member named keeps today's behavior verbatim, {@code actingRole}
     * included.
     */
    Principal narrowed(Principal principal, String appName, String acting) {
        boolean hasActing = acting != null && !acting.isBlank();
        if (appName == null || appName.isBlank()) {
            return hasActing ? activated(principal, acting) : principal;
        }
        if (memberAddresses == null || !memberAddresses.containsKey(appName)) {
            throw new io.tesseraql.core.error.TqlException(UNKNOWN_MEMBER,
                    memberAddresses == null
                            ? "The token request names stack member '" + appName + "', but this"
                                    + " runtime addresses no stack members — the member axis is"
                                    + " the stack surface's"
                            : "The token request names stack member '" + appName + "', but the"
                                    + " stack's members are " + memberAddresses.keySet());
        }
        java.util.List<io.tesseraql.security.Principal.RoleGrant> scoped = io.tesseraql.security.Activation
                .grantsFor(principal, appName);
        String selected = hasActing
                ? acting
                : scoped.size() == 1 ? scoped.get(0).role() : null;
        if (selected != null
                && scoped.stream().noneMatch(grant -> selected.equals(grant.role()))) {
            throw new io.tesseraql.core.error.TqlException(
                    io.tesseraql.security.Activation.WRONG_CAPACITY,
                    "The caller does not hold application role '" + selected + "' for member '"
                            + appName + "', so a token cannot be minted acting as it");
        }
        return io.tesseraql.security.Activation.activate(principal, appName, selected);
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
        return mint(principal, null);
    }

    /** As {@link #mint(Principal)}, with a named member's address as the audience. */
    Map<String, Object> mint(Principal principal, String appName) {
        Instant expiry = Instant.now().plus(lifetime);
        String audience = appName == null || appName.isBlank() || memberAddresses == null
                ? null
                : externalOrigin + memberAddresses.get(appName);
        String minted;
        try {
            minted = sign(principal, expiry, audience);
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
    private String sign(Principal principal, Instant expiry, String audienceOverride)
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
        // A named member's address outranks it (docs/token-issuance.md decision 9): the token
        // is minted for that member and refuses everywhere else.
        payload.put("aud", audienceOverride != null
                ? audienceOverride
                : jwt.audience().size() == 1 ? jwt.audience().get(0) : jwt.audience());
        payload.put("exp", expiry.getEpochSecond());

        // One issuer per stack: with the authorization server enabled, the session exchange and
        // the OAuth grants sign with the same database-held RS256 key, so a token from either
        // door validates against the same JWKS at every member (decision 9).
        if (stackSigner != null) {
            io.tesseraql.oauth.AccessTokenSigner signer = stackSigner.get();
            if (signer == null) {
                throw new IllegalStateException("The stack issuer is enabled but no signer is"
                        + " bound — the oauth extension did not install");
            }
            return signer.sign(payload);
        }

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
