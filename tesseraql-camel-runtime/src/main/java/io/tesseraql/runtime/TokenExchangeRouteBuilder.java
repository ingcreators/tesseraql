package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.session.CsrfValidator;
import io.tesseraql.security.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Exchanges an authenticated session for a short-lived bearer token
 * (docs/session-token-exchange.md).
 *
 * <p>Far less than an authorization server: no {@code /authorize}, no consent, no client registry,
 * no redirect handling, no refresh tokens. It is also enough for every client that runs on the
 * user's own machine — CI, scripts, Claude Code, the Codex CLI — which is the set an intranet
 * deployment can actually reach. The hosted assistants are not reachable this way and never will
 * be; they fetch from the vendor's cloud and offer no field for a fixed credential.
 *
 * <p>The token asserts nothing new. Its claims are the ones the bearer path already reads, taken
 * from the principal the session already carries, so {@link io.tesseraql.security.jwt.JwtAuthenticator}
 * validates it exactly as it validates an identity provider's.
 */
final class TokenExchangeRouteBuilder extends RouteBuilder {

    private static final System.Logger LOG = System.getLogger(
            TokenExchangeRouteBuilder.class.getName());

    /**
     * TQL-SEC-4146: issuing was enabled and there is nothing to sign with.
     *
     * <p>A boot refusal rather than a runtime one. {@code JwtAuthenticator} binds its algorithm
     * from configuration and there is no private key anywhere in the tree, so an application
     * verifying RS256 against a {@code jwksUri} cannot be issued for — it already has an issuer,
     * and minting asymmetrically would mean JWKS publication, overlapping rotation and {@code kid}.
     * Failing at startup says so once, to the operator, instead of once per request to a caller who
     * cannot act on it.
     */
    private static final TqlErrorCode NO_SIGNING_KEY = new TqlErrorCode(TqlDomain.SEC, 4146);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionStore sessions;
    private final JwtConfig jwt;
    private final Duration ttl;

    TokenExchangeRouteBuilder(SessionStore sessions, JwtConfig jwt, Duration ttl) {
        this.sessions = sessions;
        this.jwt = jwt;
        this.ttl = ttl;
    }

    /**
     * Whether this application can issue at all.
     *
     * <p>Checked by the runtime before wiring, so an application that cannot sign refuses to boot
     * with {@link #NO_SIGNING_KEY} rather than mounting an endpoint that answers 500.
     */
    static boolean canIssue(JwtConfig jwt) {
        return jwt != null && "HS256".equals(jwt.algorithm()) && jwt.secret() != null
                && !jwt.secret().isBlank();
    }

    /** The refusal an operator sees at startup when issuing is on and unsignable. */
    static TqlException noSigningKey() {
        return new TqlException(NO_SIGNING_KEY,
                "tesseraql.security.token.enabled is true, but this application has no HS256 secret"
                        + " to sign with — it verifies asymmetrically (publicKey/jwksUri) or"
                        + " configures no JWT at all, so its tokens come from the identity provider"
                        + " that holds the signing key, not from here");
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true)
                .process(new io.tesseraql.compiler.binding.ErrorResponseRenderer());
        onException(Exception.class).handled(true)
                .process(new io.tesseraql.compiler.binding.ErrorResponseRenderer());

        rest().post("/_tesseraql/token").to("direct:tql.token");
        from("direct:tql.token").routeId("system.token").process(this::exchange);
    }

    /**
     * Mints a token for the caller's own session.
     *
     * <p>A state-changing browser POST, guarded like its logout siblings. That is not ceremony: an
     * exchange endpoint raises what a stolen session cookie is worth, because it converts one into
     * a bearer token carrying none of the cookie's protections and outliving the cookie itself. The
     * CSRF check refuses when there is no session at all, so an unauthenticated caller never
     * reaches the minting path.
     */
    private void exchange(Exchange exchange) throws Exception {
        String cookie = exchange.getMessage().getHeader("Cookie", String.class);
        String token = exchange.getMessage().getHeader("X-CSRF-Token", String.class);
        if (token == null) {
            Object field = LoginRouteBuilder.parseBody(exchange).get("_csrf");
            token = field == null ? null : String.valueOf(field);
        }
        new CsrfValidator(sessions).validate(cookie, token);

        SessionStore.Session session = sessions.session(sessions.sessionIdFromCookie(cookie));
        Principal principal = session.principal();
        Instant expiry = Instant.now().plus(ttl);
        String minted = sign(principal, expiry);

        // Recorded because a token outliving the session that produced it is a credential nobody
        // would otherwise know exists.
        LOG.log(System.Logger.Level.INFO,
                "Issued a bearer token for subject {0}, expiring {1}", principal.subject(), expiry);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(Map.of(
                "token", minted,
                "tokenType", "Bearer",
                "expiresAt", expiry.toString())));
    }

    /** The claims the bearer path reads, signed with the secret it verifies against. */
    private String sign(Principal principal, Instant expiry) throws Exception {
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
}
