package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.LoginRedirects;
import io.tesseraql.security.session.SessionStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Password login/logout endpoints (design ch. 10.8, 11.2):
 * <ul>
 *   <li>{@code POST /_tesseraql/login} — authenticates and creates a browser session. A JSON caller
 *       (an API client) gets {@code {ok:true,...}} with the session cookie; a browser form post
 *       (the bundled login page, {@code application/x-www-form-urlencoded}) is redirected to its
 *       sanitized {@code redirect} target (post/redirect/get), or back to the login page on failure.</li>
 *   <li>{@code GET /_tesseraql/logout} — invalidates the session, clears the cookie, redirects to
 *       the login page.</li>
 * </ul>
 * OIDC and SAML logins (the optional extensions) create the <em>same</em> session, so any
 * {@code auth: browser} route is satisfied however the session was established.
 */
final class LoginRoutes {

    private static final String LOGIN_PATH = "/_tesseraql/login";

    private static final io.tesseraql.core.error.TqlErrorCode BAD_REQUEST_BODY = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.FIELD, 2002);

    private static final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
    private final PasswordAuthenticator authenticator;
    private final RealmConfig realm;
    private final SessionStore sessions;
    private final io.tesseraql.core.credential.TotpStore totp;
    private final io.tesseraql.security.throttle.CredentialThrottle throttle;
    private final io.tesseraql.identity.IdentityService identity;

    LoginRoutes(PasswordAuthenticator authenticator, RealmConfig realm,
            SessionStore sessions, io.tesseraql.core.credential.TotpStore totp,
            io.tesseraql.security.throttle.CredentialThrottle throttle,
            io.tesseraql.identity.IdentityService identity) {
        this.authenticator = authenticator;
        this.realm = realm;
        this.sessions = sessions;
        this.totp = totp;
        this.throttle = throttle;
        this.identity = identity;
    }

    /** The presented address, the ClientInfo resolution: XFF first, else the peer. */
    static String presentedAddress(Exchange exchange) {
        return SessionStore.ClientInfo.of(null,
                exchange.request().header("X-Forwarded-For"),
                exchange.request().remoteAddress())
                .remoteAddr();
    }

    /**
     * Answers a throttled credential attempt (docs/credential-throttle.md): a browser
     * form bounces to the login page's rate message, an API caller gets the 429 envelope
     * with Retry-After. Reveals the throttle, never the account. The session-expiry
     * dialog's attempt re-renders the dialog instead of bouncing — a redirect would
     * navigate the page whose work the dialog exists to preserve.
     */
    static void renderThrottled(Exchange exchange, boolean browserForm,
            java.time.Duration wait, String next) throws Exception {
        if (browserForm && isHtmx(exchange)) {
            exchange.response().status(429);
            exchange.response().header("Retry-After",
                    String.valueOf(Math.max(1, wait.toSeconds())));
            renderDialog(exchange, "tql.session.throttled");
            return;
        }
        if (browserForm) {
            redirect(exchange, 303, LOGIN_PATH + "?error=rate"
                    + (next == null
                            ? ""
                            : "&redirect="
                                    + URLEncoder.encode(next, StandardCharsets.UTF_8)));
            return;
        }
        exchange.response().status(429);
        exchange.response().header("Retry-After", String.valueOf(Math.max(1, wait.toSeconds())));
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.setBody(io.tesseraql.core.error.ErrorEnvelope.json(THROTTLED,
                "Too many attempts; retry later"));
    }

    /** TQL-RATE-4292: too many sign-in attempts from this address (HTTP 429). */
    private static final io.tesseraql.core.error.TqlErrorCode THROTTLED = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.RATE, 4292);

    void install(RuntimeContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.of(context).mount("POST", LOGIN_PATH, "system.login");
        pipelines.pipeline("system.login").process(this::login);

        // Sign-out is a state change: a POST with the CSRF token, like its logout-device
        // and logout-others siblings — the CSRF-exempt GET is gone
        // (docs/vocabulary-cleanup.md slice 3).
        HttpMounts.of(context).mount("POST", "/_tesseraql/logout", "system.logout");
        pipelines.pipeline("system.logout").process(this::logout);

        // Sign out every session but this one (roadmap Phase 48, the account surface). A
        // state-changing browser POST outside the compiled route pipeline, so the CSRF check
        // runs here explicitly - same validator, header or hidden-field token.
        // Per-device sign-out (docs/session-visibility.md): ends the caller's session
        // named by its handle. A Java route like logout-others, because only this layer
        // can read the cookie - and clear it when the revoked device was this one.
        HttpMounts.of(context).mount("POST", "/_tesseraql/logout-device",
                "system.logout.device");
        pipelines.pipeline("system.logout.device")
                .process(this::logoutDevice);

        HttpMounts.of(context).mount("POST", "/_tesseraql/logout-others",
                "system.logout.others");
        pipelines.pipeline("system.logout.others")
                .process(this::logoutOthers);

        // Just-in-time elevation (docs/access-governance.md structural decision 3). A Java
        // route for the same reason as its logout siblings: only this layer reads the
        // cookie, and taking an eligible role has to reach the caller's own session — a
        // principal frozen at sign-in would otherwise hold the new role only after a
        // re-login, which makes the feature useless for its purpose.
        HttpMounts.of(context).mount("POST", "/_tesseraql/account/elevate",
                "system.account.elevate");
        pipelines.pipeline("system.account.elevate")
                .process(this::elevate);
    }

    private void login(Exchange exchange) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        boolean browserForm = isFormPost(exchange);
        String loginId = str(body.get("loginId"));
        String password = str(body.get("password"));
        String tenantId = str(body.get("tenantId"));
        String next = safeNext(body.get("redirect"));

        // Before any existence check or hash computation (docs/credential-throttle.md):
        // a throttled request pays nothing and learns nothing about the account.
        String address = presentedAddress(exchange);
        var wait = throttle.retryAfter("login", loginId, address);
        if (wait.isPresent()) {
            renderThrottled(exchange, browserForm, wait.get(), next);
            return;
        }

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
            throttle.recordFailure(loginId, address);
            if (browserForm && isHtmx(exchange)) {
                // The session-expiry dialog's attempt failed: re-render the dialog with the
                // error inline (the recipe's 422 shape) — same anonymous message as the
                // login page, whatever the cause (wrong password, wrong or replayed code).
                exchange.response().status(422);
                renderDialog(exchange, "tql.session.invalid");
                return;
            }
            if (browserForm) {
                // Post/redirect/get: bounce back to the login page with an error flag and the
                // original target, so a refresh does not re-submit the credentials.
                redirect(exchange, 303, LOGIN_PATH + "?error=1&redirect="
                        + URLEncoder.encode(next, StandardCharsets.UTF_8));
                return;
            }
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid credentials");
        }

        // Client facts ride into the session for the visibility surfaces
        // (docs/session-visibility.md): informational, recorded as presented. Admitting the
        // address they were read from is layer A (docs/access-governance.md structural
        // decision 8) — after the credential is proven, because a network refusal must not
        // tell an outsider whether the password was right.
        throttle.recordSuccess(loginId);
        String sessionId = sessions.create(principal.get(),
                io.tesseraql.pipeline.auth.SignInAdmission.admitted(exchange));
        setSessionCookie(exchange, io.tesseraql.security.session.SessionCookie.issue(
                sessions.cookieName(), sessionId, io.tesseraql.pipeline.CookiePath.of(exchange)));
        if (browserForm && isHtmx(exchange)) {
            // The session-expiry dialog's attempt succeeded (the recipe's 200): no body, only
            // the hc:sessionrenewed trigger — the kit closes the dialog and replays the
            // interrupted request. The fresh session's CSRF token rides in the payload
            // because the page's <meta> still carries the dead session's token, and the
            // bootstrap must swap it before the replay's installCsrfHeader reads it.
            exchange.response().status(200);
            exchange.response().header("HX-Trigger", mapper.writeValueAsString(
                    Map.of("hc:sessionrenewed",
                            Map.of("csrfToken", sessions.csrfToken(sessionId)))));
            exchange.response().header(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
            exchange.setBody("");
            return;
        }
        if (browserForm) {
            redirect(exchange, 303, next);
            return;
        }
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        // The CSRF token rides back with the cookie (docs/stack-architecture.md Decision 20). A
        // non-browser caller that authenticates here could not proceed to any guarded route
        // without it — POST /_tesseraql/token most of all — because the token reached pages only,
        // as <meta name="csrf-token">, and a command-line client parses no HTML. Returning it
        // grants no new capability: the same value already reaches any authenticated browser
        // through that tag, and a hostile page still cannot read a cross-origin response body.
        exchange.setBody(mapper.writeValueAsString(
                Map.of("ok", true, "loginId", principal.get().loginId(),
                        "csrfToken", sessions.csrfToken(sessionId))));
    }

    private void logout(Exchange exchange) throws Exception {
        String cookie = exchange.request().header("Cookie");
        String token = exchange.request().header("X-CSRF-Token");
        if (token == null) {
            Object field = parseBody(exchange).get("_csrf");
            token = field == null ? null : String.valueOf(field);
        }
        new io.tesseraql.security.session.CsrfValidator(sessions).validate(cookie, token);
        sessions.invalidateFromCookie(cookie);
        // Expire the cookie client-side too (Max-Age=0), then land on the login page.
        setSessionCookie(exchange, io.tesseraql.security.session.SessionCookie.expire(
                sessions.cookieName(), io.tesseraql.pipeline.CookiePath.of(exchange)));
        redirect(exchange, 303, LOGIN_PATH);
    }

    /**
     * Invalidates one of the caller's sessions by its public handle
     * (docs/session-visibility.md). Subject-scoped through the caller's own session, so a
     * posted handle can never name another subject's device. Revoking the device that made
     * this request is an ordinary sign-out: cookie cleared, back to the login page.
     */
    private void logoutDevice(Exchange exchange) throws Exception {
        AuthenticatedPost post = requireSessionAndCsrf(exchange);
        String sessionId = post.sessionId();
        Object handle = post.body().get("handle");
        if (handle != null && !String.valueOf(handle).isBlank()) {
            sessions.invalidateByHandle(post.session().principal().subject(),
                    String.valueOf(handle));
        }
        if (sessions.session(sessionId) == null) {
            // The revoked device was this one: an ordinary sign-out.
            setSessionCookie(exchange, io.tesseraql.security.session.SessionCookie.expire(
                    sessions.cookieName(), io.tesseraql.pipeline.CookiePath.of(exchange)));
            redirect(exchange, 303, LOGIN_PATH);
            return;
        }
        redirect(exchange, 303, "/_tesseraql/account?saved=signed-out-device");
    }

    /** Invalidates the caller's other sessions, keeping the one that made this request. */
    private void logoutOthers(Exchange exchange) throws Exception {
        AuthenticatedPost post = requireSessionAndCsrf(exchange);
        sessions.invalidateOthersFor(post.session().principal().subject(), post.sessionId());
        redirect(exchange, 303, "/_tesseraql/account?saved=signed-out-others");
    }

    /**
     * Takes an eligible role for a bounded window and makes it live in this session
     * (docs/access-governance.md structural decision 3). Two halves: the store write, which
     * is an ordinary windowed grant, and the session refresh, which is the only part that
     * needed building — a frozen principal would otherwise hold the elevation no sooner
     * than the caller's next sign-in.
     *
     * <p>The re-resolution is narrow on purpose. It re-reads this caller's own principal
     * into this caller's own session; it is not a general mid-session refresh, and the
     * person's other sessions see the elevation at their next sign-in.
     */
    private void elevate(Exchange exchange) throws Exception {
        AuthenticatedPost post = requireSessionAndCsrf(exchange);
        SessionStore.Session session = post.session();
        Map<String, Object> body = post.body();
        if (identity == null) {
            throw new TqlException(io.tesseraql.identity.Elevation.REFUSED,
                    "This deployment has no identity realm to elevate against");
        }
        // The subject is the session's, never the request's: a caller may only ever
        // elevate themselves, so there is no target to validate.
        String subject = session.principal().subject();
        String role = str(body.get("roleCode"));
        if ("1".equals(str(body.get("end"))) || "true".equals(str(body.get("end")))) {
            io.tesseraql.identity.Elevation.endElevation(identity, realm, subject, subject,
                    role);
        } else {
            io.tesseraql.identity.Elevation.elevate(identity, realm, subject, role,
                    str(body.get("minutes")), str(body.get("reason")));
        }
        refreshPrincipal(post.sessionId(), session);
        redirect(exchange, 303, "/_tesseraql/account?saved=elevated");
    }

    /**
     * The caller a session-mutating POST acts for: the live session proven by the cookie, and
     * the CSRF token — the {@code X-CSRF-Token} header, else the {@code _csrf} form field —
     * validated against it. This preamble existed as three drifting copies (the review's one
     * real CPD hit); the parsed body rides along because two of the three read it anyway.
     */
    private record AuthenticatedPost(String sessionId, SessionStore.Session session,
            Map<String, Object> body) {
    }

    private AuthenticatedPost requireSessionAndCsrf(Exchange exchange) throws Exception {
        String cookie = exchange.request().header("Cookie");
        String sessionId = sessions.sessionIdFromCookie(cookie);
        SessionStore.Session session = sessionId == null ? null : sessions.session(sessionId);
        if (session == null) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "No session");
        }
        Map<String, Object> body = parseBody(exchange);
        String token = exchange.request().header("X-CSRF-Token");
        if (token == null) {
            Object field = body.get("_csrf");
            token = field == null ? null : String.valueOf(field);
        }
        new io.tesseraql.security.session.CsrfValidator(sessions).validate(cookie, token);
        return new AuthenticatedPost(sessionId, session, body);
    }

    /** Re-reads the session owner's principal so the change is live on the next request. */
    private void refreshPrincipal(String sessionId, SessionStore.Session session) {
        String loginId = session.principal().loginId();
        if (loginId == null) {
            return;
        }
        identity.resolvePrincipal(realm, loginId, session.principal().tenantId())
                .ifPresent(fresh -> sessions.replacePrincipal(sessionId, fresh));
    }

    /** Shared with the recovery endpoints (roadmap Phase 50), same package. */
    static void redirect(Exchange exchange, int status, String location) {
        io.tesseraql.compiler.binding.RedirectRenderer.negotiate(exchange, status, location);
    }

    private static void setSessionCookie(Exchange exchange, String cookie) {
        exchange.response().addHeader("Set-Cookie", cookie);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Sanitizes the post-login {@code redirect} target: only a same-origin absolute path is
     * honored, so a crafted {@code redirect} cannot send the freshly-authenticated browser
     * off-site (an open redirect). Anything else falls back to the app root.
     */
    private static String safeNext(Object raw) {
        return LoginRedirects.sanitize(str(raw), "/");
    }

    /** Whether the request came from htmx — the session-expiry dialog's own legs included. */
    private static boolean isHtmx(Exchange exchange) {
        return "true".equals(exchange.request().header("HX-Request"));
    }

    /**
     * Re-renders the session-expiry dialog into the shell's shared host with the given error
     * message inline — the failure legs of the dialog's own login post. Default i18n settings,
     * like every renderer on these framework pipelines.
     */
    private static void renderDialog(Exchange exchange, String messageKey) {
        io.tesseraql.yaml.i18n.I18nSettings i18n = io.tesseraql.yaml.i18n.I18nSettings
                .defaults();
        String tag = exchange.getProperty(io.tesseraql.pipeline.TesseraqlProperties.LOCALE,
                i18n.defaultTag(), String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> methods = exchange.beans().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.LOGIN_METHODS_BEAN, Map.class);
        exchange.response().header("HX-Retarget", "[data-hc-session-expiry]");
        exchange.response().header("HX-Reswap", "innerHTML");
        exchange.response().header(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.setBody(io.tesseraql.compiler.binding.SessionExpiredDialog.render(exchange,
                i18n, tag, methods == null ? Map.of() : methods,
                io.tesseraql.compiler.binding.SessionExpiredDialog.alert(i18n, tag,
                        messageKey)));
    }

    private static boolean isFormPost(Exchange exchange) {
        String contentType = exchange.request().header(Headers.CONTENT_TYPE);
        return contentType != null && contentType.contains("application/x-www-form-urlencoded");
    }

    /** Shared with the recovery endpoints (roadmap Phase 50), same package. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseBody(Exchange exchange) throws Exception {
        // A form has one representation (docs/vertx-native.md decision 2): the edge parsed it.
        if (!exchange.request().formFields().isEmpty()) {
            Map<String, Object> form = new LinkedHashMap<>();
            exchange.request().formFields()
                    .forEach((name, values) -> form.put(name, values.get(0)));
            return form;
        }
        String raw = exchange.getBody(String.class);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(raw, Map.class);
            // A body of the literal `null` parses to null, and every caller here dereferences
            // the map — so without this the caller's own mistake arrived as a NullPointerException
            // and left as an internal server error, exactly like the parse failure below.
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            // The sentence travels in details, not in the message: ErrorResponseRenderer replaces
            // an envelope's message with the localized status phrase, so a message-only throw
            // answers "Bad Request" and renders an alert with an empty body.
            throw io.tesseraql.core.error.TqlException.builder(BAD_REQUEST_BODY)
                    .message("The request body must be a JSON object")
                    .details(Map.of("message", "The request body must be a JSON object"))
                    .build();
        }
    }
}
