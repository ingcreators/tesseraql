package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.LoginRedirects;
import io.tesseraql.security.session.SessionStore;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Password login/logout endpoints (design ch. 10.8, 11.2):
 * <ul>
 *   <li>{@code POST /_tesseraql/login} — authenticates and creates a browser session. A JSON caller
 *       (an API client) gets {@code {ok:true,...}} with the session cookie; a browser form post
 *       (the bundled login page, {@code application/x-www-form-urlencoded}) is redirected to its
 *       sanitized {@code next} target (post/redirect/get), or back to the login page on failure.</li>
 *   <li>{@code GET /_tesseraql/logout} — invalidates the session, clears the cookie, redirects to
 *       the login page.</li>
 * </ul>
 * OIDC and SAML logins (the optional extensions) create the <em>same</em> session, so any
 * {@code auth: browser} route is satisfied however the session was established.
 */
final class LoginRouteBuilder extends RouteBuilder {

    private static final String LOGIN_PATH = "/_tesseraql/login";

    private static final ObjectMapper mapper = new ObjectMapper();
    private final PasswordAuthenticator authenticator;
    private final RealmConfig realm;
    private final SessionStore sessions;
    private final io.tesseraql.core.credential.TotpStore totp;

    LoginRouteBuilder(PasswordAuthenticator authenticator, RealmConfig realm,
            SessionStore sessions, io.tesseraql.core.credential.TotpStore totp) {
        this.authenticator = authenticator;
        this.realm = realm;
        this.sessions = sessions;
        this.totp = totp;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().post(LOGIN_PATH).to("direct:tql.login");
        from("direct:tql.login").routeId("system.login").process(this::login);

        rest().get("/_tesseraql/logout").to("direct:tql.logout");
        from("direct:tql.logout").routeId("system.logout").process(this::logout);

        // Sign out every session but this one (roadmap Phase 48, the account surface). A
        // state-changing browser POST outside the compiled route pipeline, so the CSRF check
        // runs here explicitly - same validator, header or hidden-field token.
        rest().post("/_tesseraql/logout-others").to("direct:tql.logoutOthers");
        from("direct:tql.logoutOthers").routeId("system.logout.others")
                .process(this::logoutOthers);
    }

    private void login(Exchange exchange) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        boolean browserForm = isFormPost(exchange);
        String loginId = str(body.get("loginId"));
        String password = str(body.get("password"));
        String tenantId = str(body.get("tenantId"));
        String next = safeNext(body.get("next"));

        Optional<Principal> principal = authenticator.authenticate(realm, loginId, password,
                tenantId);
        // A confirmed TOTP enrollment makes the code field required (roadmap Phase 50
        // slice 3). Missing, wrong, and REPLAYED codes all collapse into the same
        // invalid-credentials answer as a wrong password - winning the store's
        // last-used-step compare-and-set is what accepts a code.
        if (principal.isPresent() && totp != null) {
            var enrollment = totp.enrollment(principal.get().tenantId(),
                    principal.get().subject()).filter(e -> e.confirmed());
            if (enrollment.isPresent()) {
                long step = io.tesseraql.security.totp.Totp.matchedStep(
                        enrollment.get().secret(), str(body.get("otp")));
                boolean accepted = step >= 0 && totp.markUsedStep(principal.get().tenantId(),
                        principal.get().subject(), step);
                // A recovery code signs in once when the authenticator is lost: consuming
                // (deleting) the hash is the single-use guarantee, and a wrong code stays a
                // wrong-password-shaped answer (docs/credential-lifecycle.md).
                String otp = str(body.get("otp"));
                if (!accepted && otp != null && !otp.isBlank()) {
                    accepted = totp.consumeRecoveryCode(principal.get().tenantId(),
                            principal.get().subject(), AccountViews.recoveryHash(otp));
                }
                if (!accepted) {
                    principal = Optional.empty();
                }
            }
        }
        if (principal.isEmpty()) {
            if (browserForm) {
                // Post/redirect/get: bounce back to the login page with an error flag and the
                // original target, so a refresh does not re-submit the credentials.
                redirect(exchange, 303, LOGIN_PATH + "?error=1&next="
                        + URLEncoder.encode(next, StandardCharsets.UTF_8));
                return;
            }
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid credentials");
        }

        String sessionId = sessions.create(principal.get());
        setSessionCookie(exchange, sessions.cookieName() + "=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Lax");
        if (browserForm) {
            redirect(exchange, 303, next);
            return;
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(mapper.writeValueAsString(
                Map.of("ok", true, "loginId", principal.get().loginId())));
    }

    private void logout(Exchange exchange) {
        sessions.invalidateFromCookie(exchange.getMessage().getHeader("Cookie", String.class));
        // Expire the cookie client-side too (Max-Age=0), then land on the login page.
        setSessionCookie(exchange, sessions.cookieName()
                + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        redirect(exchange, 303, LOGIN_PATH);
    }

    /** Invalidates the caller's other sessions, keeping the one that made this request. */
    private void logoutOthers(Exchange exchange) throws Exception {
        String cookie = exchange.getMessage().getHeader("Cookie", String.class);
        String sessionId = sessions.sessionIdFromCookie(cookie);
        SessionStore.Session session = sessionId == null ? null : sessions.session(sessionId);
        if (session == null) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "No session");
        }
        String token = exchange.getMessage().getHeader("X-CSRF-Token", String.class);
        if (token == null) {
            Object field = parseBody(exchange).get("_csrf");
            token = field == null ? null : String.valueOf(field);
        }
        new io.tesseraql.security.session.CsrfValidator(sessions).validate(cookie, token);
        sessions.invalidateOthersFor(session.principal().subject(), sessionId);
        redirect(exchange, 303, "/_tesseraql/account");
    }

    /** Shared with the recovery endpoints (roadmap Phase 50), same package. */
    static void redirect(Exchange exchange, int status, String location) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader("Location", location);
        exchange.getMessage().setBody("");
    }

    private static void setSessionCookie(Exchange exchange, String cookie) {
        exchange.getMessage().setHeader("Set-Cookie", cookie);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Sanitizes a post-login {@code next} target: only a same-origin absolute path is honored, so a
     * crafted {@code next} cannot redirect the freshly-authenticated browser off-site (an open
     * redirect). Anything else falls back to the app root.
     */
    private static String safeNext(Object raw) {
        return LoginRedirects.sanitize(str(raw), "/");
    }

    private static boolean isFormPost(Exchange exchange) {
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        return contentType != null && contentType.contains("application/x-www-form-urlencoded");
    }

    /** Shared with the recovery endpoints (roadmap Phase 50), same package. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseBody(Exchange exchange) throws Exception {
        // platform-http may pre-parse a browser form post into a Map body; use it directly.
        if (exchange.getMessage().getBody() instanceof Map<?, ?> formBody) {
            Map<String, Object> form = new LinkedHashMap<>();
            formBody.forEach((key, value) -> form.put(String.valueOf(key), value));
            return form;
        }
        String raw = exchange.getMessage().getBody(String.class);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        if (isFormPost(exchange)) {
            return parseForm(raw);
        }
        return mapper.readValue(raw, Map.class);
    }

    /** Parses a {@code application/x-www-form-urlencoded} body (the bundled login form). */
    private static Map<String, Object> parseForm(String raw) {
        Map<String, Object> form = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }
}
