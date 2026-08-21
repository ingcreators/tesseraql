package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.session.CsrfValidator;
import io.tesseraql.security.session.SessionStore;
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
 *
 * <p>This is the JSON face. The console's issue-token page is the other one, and both mint through
 * {@link SessionTokens} so the two cannot drift.
 */
final class TokenExchangeRouteBuilder extends RouteBuilder {

    /**
     * TQL-SEC-4146: issuing was enabled and there is nothing to sign with — neither an HS256
     * secret nor the stack's authorization server.
     *
     * <p>A boot refusal rather than a runtime one, and narrower than it once was: the original
     * premise ("there is no private key anywhere in the tree") died with the authorization
     * server (docs/token-issuance.md decision 9), whose database-held key signs the exchange's
     * tokens wherever {@code security.oauth.enabled} reaches. An application that verifies
     * asymmetrically against an <em>external</em> issuer's {@code jwksUri} still cannot be
     * issued for here — its tokens come from that issuer, not from this endpoint. Failing at
     * startup says so once, to the operator, instead of once per request to a caller who cannot
     * act on it.
     */
    private static final TqlErrorCode NO_SIGNING_KEY = new TqlErrorCode(TqlDomain.SEC, 4146);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionStore sessions;
    private final SessionTokens tokens;

    TokenExchangeRouteBuilder(SessionStore sessions, SessionTokens tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
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
                "tesseraql.security.token.enabled is true, but this application has no HS256"
                        + " secret to sign with and the stack's authorization server is not"
                        + " enabled — it verifies against an external issuer (publicKey/jwksUri)"
                        + " or configures no JWT at all, so its tokens come from the issuer that"
                        + " holds a signing key: that provider, or the stack file's"
                        + " security.oauth.enabled");
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true)
                .process(new io.tesseraql.compiler.binding.ErrorResponseRenderer());
        onException(Exception.class).handled(true)
                .process(new io.tesseraql.compiler.binding.ErrorResponseRenderer());

        HttpMounts.mount(getContext(), "POST", "/_tesseraql/token", "direct:tql.token");
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
        java.util.Map<String, Object> body = LoginRouteBuilder.parseBody(exchange);
        String token = exchange.getMessage().getHeader("X-CSRF-Token", String.class);
        if (token == null) {
            Object field = body.get("_csrf");
            token = field == null ? null : String.valueOf(field);
        }
        new CsrfValidator(sessions).validate(cookie, token);

        SessionStore.Session session = sessions.session(sessions.sessionIdFromCookie(cookie));

        // The token face of activation (docs/application-roles.md) and the member axis of the
        // unified issuer (docs/token-issuance.md decision 9): `tesseraql token --as` states a
        // capacity, `--app-name` states a member; the mint reads the session principal's own
        // grants, so both statements can only narrow — an unheld role is TQL-SEC-4148, an
        // unaddressed member TQL-OAUTH-3003.
        io.tesseraql.security.Principal principal = session.principal();
        Object acting = body.get("actingRole");
        Object app = body.get("appName");
        String appName = app == null ? null : String.valueOf(app);
        principal = tokens.narrowed(principal, appName,
                acting == null ? null : String.valueOf(acting));

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(
                MAPPER.writeValueAsString(tokens.mint(principal, appName)));
    }
}
