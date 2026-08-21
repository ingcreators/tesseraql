package io.tesseraql.camel.auth;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.security.Activation;
import io.tesseraql.security.Principal;
import io.tesseraql.security.apikey.ApiKeyAuthenticator;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.mtls.MtlsAuthenticator;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.BrowserAuthenticator;
import io.tesseraql.security.session.CsrfValidator;
import io.tesseraql.security.session.SessionStore;

/**
 * Performs the {@code authenticate} and {@code authorize} operations for the {@code tesseraql-auth}
 * component (design ch. 9.2, 7.2). The {@link PolicyEngine} and {@link JwtAuthenticator} are looked
 * up from the Camel registry, where the runtime binds them from the security configuration.
 */
public class AuthStep implements Step {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.SEC, 4000);

    /** TQL-SEC-4001: the authenticator this route needs is not configured, so nothing can pass. */
    private static final TqlErrorCode NOT_CONFIGURED = new TqlErrorCode(TqlDomain.SEC, 4001);

    private final String operation;
    private final String auth;
    private final String policy;
    private final String pathTemplate;

    /** The gate a route declares, with the settings its endpoint URI used to carry. */
    public AuthStep(String operation, String auth, String policy, String pathTemplate) {
        this.operation = operation;
        this.auth = auth == null ? "bearer" : auth;
        this.policy = policy;
        this.pathTemplate = pathTemplate;
    }

    /** A gate with no settings beyond its operation: csrf, rotate, fence, conditions, activate. */
    public AuthStep(String operation) {
        this(operation, null, null, null);
    }

    /** Which gate this is. */
    public String operation() {
        return operation;
    }

    /** The authentication kind an {@code authenticate} gate demands. */
    public String auth() {
        return auth;
    }

    /** The permission atom an {@code authorize} gate checks, possibly parameterised. */
    public String policy() {
        return policy;
    }

    /** The route's URL template, which is where a parameterised atom reads its parameter from. */
    public String pathTemplate() {
        return pathTemplate;
    }

    @Override
    public void process(Exchange exchange) {
        io.tesseraql.core.telemetry.Span span = io.tesseraql.camel.TesseraqlTracing.tracer(exchange)
                .start("tesseraql.security." + operation,
                        io.tesseraql.camel.TesseraqlTracing.parent(exchange))
                .attribute("operation", operation);
        if (auth != null) {
            span.attribute("auth", auth);
        }
        if (policy != null) {
            span.attribute("policy", policy);
        }
        try {
            switch (operation) {
                case "authenticate" -> authenticate(exchange);
                case "authorize" -> authorize(exchange, span);
                case "csrf" -> csrf(exchange);
                case "rotate" -> rotate(exchange);
                case "fence" -> fence(exchange);
                case "conditions" -> conditions(exchange);
                case "activate" -> activate(exchange);
                default -> throw new TqlException(UNSUPPORTED,
                        "Unsupported tesseraql-auth operation: " + operation);
            }
        } catch (RuntimeException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Re-issues the caller's session cookie in place (docs/session-rotation.md): a fresh
     * id and CSRF token, the old id invalidated first. No session cookie (a bearer or
     * public caller on the same route) and no session store are both no-ops - the
     * directive describes browser sessions, and a shared route must not fail for the
     * caller without one.
     */
    private void rotate(Exchange exchange) {
        SessionStore sessions = exchange.beans().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        if (sessions == null) {
            return;
        }
        String cookieHeader = exchange.getMessage().getHeader("Cookie", String.class);
        String fresh = sessions.rotate(sessions.sessionIdFromCookie(cookieHeader));
        if (fresh != null) {
            exchange.getMessage().setHeader("Set-Cookie",
                    io.tesseraql.security.session.SessionCookie.issue(sessions.cookieName(),
                            fresh, io.tesseraql.camel.CookiePath.of(exchange)));
        }
    }

    private void authenticate(Exchange exchange) {
        Principal principal = switch (auth) {
            case "bearer" ->
                bean(exchange, JwtAuthenticator.class, TesseraqlProperties.JWT_AUTHENTICATOR_BEAN)
                        .authenticate(
                                exchange.getMessage().getHeader("Authorization", String.class));
            case "api-key" -> apiKeyAuthenticate(exchange);
            case "mtls" -> mtlsAuthenticate(exchange);
            case "browser" -> browserAuthenticate(exchange);
            default ->
                throw new TqlException(UNSUPPORTED, "Unsupported auth type: " + auth);
        };
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principal);
    }

    /**
     * Resolves a service caller's API key, presented either in the configured key header (default
     * {@code X-API-Key}) or as {@code Authorization: ApiKey <key>} for gateways that forward only
     * the {@code Authorization} header (design ch. 11.1).
     */
    private Principal apiKeyAuthenticate(Exchange exchange) {
        ApiKeyAuthenticator authenticator = bean(exchange, ApiKeyAuthenticator.class,
                TesseraqlProperties.API_KEY_AUTHENTICATOR_BEAN);
        String key = exchange.getMessage().getHeader(authenticator.header(), String.class);
        if (key == null) {
            String authorization = exchange.getMessage().getHeader("Authorization", String.class);
            if (authorization != null
                    && authorization.regionMatches(true, 0, "ApiKey ", 0, "ApiKey ".length())) {
                key = authorization.substring("ApiKey ".length()).trim();
            }
        }
        return authenticator.authenticate(key);
    }

    /**
     * Resolves a service caller's mutual-TLS identity from the client certificate a trusted
     * TLS-terminating edge forwards in the configured header (URL-encoded PEM). The runtime never
     * terminates TLS itself, so the certificate arrives as a header value, not from the connection
     * (design ch. 11.1).
     */
    private Principal mtlsAuthenticate(Exchange exchange) {
        MtlsAuthenticator authenticator = bean(exchange, MtlsAuthenticator.class,
                TesseraqlProperties.MTLS_AUTHENTICATOR_BEAN);
        String header = authenticator.header();
        String certificate = header == null
                ? null
                : exchange.getMessage().getHeader(header, String.class);
        return authenticator.authenticate(certificate);
    }

    /**
     * Resolves the browser session and stashes its CSRF token, so an HTML response can publish it
     * as {@code <meta name="csrf-token">} (the {@code installCsrfHeader} convention) — the same
     * token the {@code csrf} operation later validates.
     */
    private Principal browserAuthenticate(Exchange exchange) {
        SessionStore sessions = bean(exchange, SessionStore.class,
                TesseraqlProperties.SESSION_STORE_BEAN);
        String cookie = exchange.getMessage().getHeader("Cookie", String.class);
        Principal principal = new BrowserAuthenticator(sessions).authenticate(cookie);
        if (principal != null) {
            // Feeds the idle window and the "last active" column; the store throttles
            // the write (docs/session-visibility.md).
            sessions.touch(sessions.sessionIdFromCookie(cookie));
        }
        String token = sessions.csrfTokenFromCookie(cookie);
        if (token != null) {
            exchange.setProperty(TesseraqlProperties.CSRF_TOKEN, token);
        }
        return principal;
    }

    /**
     * The member fence (docs/stack-shells.md structural decision 3): on a hosted stack member,
     * an authenticated principal without {@code tql.app.use.<member>} is refused before any
     * application route runs — reach into an application is a property of the principal, not of
     * knowing a URL. The compiler emits this step after every {@code authenticate}; anywhere but
     * a hosted member the topology bean is absent and the step is a no-op, which is exactly the
     * unhosted boot keeping its old behaviour. Public routes never authenticate, so they never
     * reach the fence; service callers (JWT, API keys, mTLS) meet it exactly as browser sessions
     * do, because a principal is a principal.
     */
    private void fence(Exchange exchange) {
        String member = exchange.beans().lookup(
                TesseraqlProperties.STACK_MEMBER_BEAN, String.class);
        if (member == null) {
            return;
        }
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        if (principal != null
                && !io.tesseraql.security.policy.Atoms.appUse(principal.permissions(), member)) {
            throw new TqlException(PolicyEngine.FORBIDDEN, "Principal is not granted "
                    + io.tesseraql.security.policy.Atoms.APP_USE_PREFIX + member
                    + ", which using this application requires (deny by default)");
        }
    }

    private void authorize(Exchange exchange, io.tesseraql.core.telemetry.Span span) {
        PolicyEngine engine = bean(exchange, PolicyEngine.class,
                TesseraqlProperties.POLICY_ENGINE_BEAN);
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        engine.authorize(policyFor(exchange, span), principal);
    }

    /**
     * The policy this request is checked against: the declared id, or — for a surface addressed
     * to one application — the atom resolved from the request's own path
     * (docs/access-governance.md structural decision 7).
     *
     * <p>The value is matched off the request's URL against the route's own template, never off
     * the path-parameter headers the router publishes: a form body publishes its fields as
     * headers too, so a field named after the path parameter overwrites one, and the gate would
     * then be resolving from the caller's own body.
     *
     * <p>A path that resolves to no usable atom segment is a denial, not a fault. The request
     * named no application the grammar admits, and the deny-by-default answer for that is the
     * one the engine would give anyway — reached here without a policy id to hand it.
     */
    private String policyFor(Exchange exchange, io.tesseraql.core.telemetry.Span span) {
        String declared = policy;
        if (!io.tesseraql.security.policy.PolicyTemplate.isTemplate(declared)) {
            return declared;
        }
        String resolved = io.tesseraql.security.policy.PolicyTemplate.resolve(declared,
                pathTemplate, wirePath(exchange));
        if (resolved == null) {
            throw new TqlException(PolicyEngine.FORBIDDEN, "Policy '" + declared + "' resolves"
                    + " from this request's path, which names no application it can check");
        }
        // The template is on the span already; the atom actually checked is what an audit of
        // "who was allowed into which application" needs to read.
        span.attribute("policy.resolved", resolved);
        return resolved;
    }

    /**
     * Grant-level context conditions (docs/access-governance.md structural decision 8, layer B):
     * a held role whose conditions this request does not satisfy is dropped from the exchange's
     * principal before anything reads it.
     *
     * <p>Placed after the fence and before activation, because it is the same arithmetic as
     * activation over the same grants — narrowing first means activation chooses among roles
     * that are actually usable here and now, and the role picker offers the same set.
     *
     * <p><b>No topology guard</b>, unlike its two neighbours. A role's conditions are a property
     * of the grant, not of the stack, so a single-application runtime evaluates them the same
     * way a hosted member does. The cost on a deployment that uses none is one list scan that
     * finds no conditions and returns the principal it was given.
     */
    private void conditions(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        if (principal == null) {
            return;
        }
        java.time.ZoneId zone = exchange.beans().lookup(
                TesseraqlProperties.CONDITION_ZONE_BEAN, java.time.ZoneId.class);
        // The presented address, resolved exactly as the session records it: the edge's
        // forwarded value when there is one, else the peer of the connection.
        String address = SessionStore.ClientInfo.of(null,
                exchange.getMessage().getHeader("X-Forwarded-For", String.class),
                exchange.getMessage().getHeader("CamelVertxPlatformHttpRemoteAddress",
                        String.class))
                .remoteAddr();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL,
                io.tesseraql.security.GrantConditions.narrow(principal, address,
                        java.time.ZonedDateTime.now(
                                zone == null ? java.time.ZoneId.systemDefault() : zone)));
    }

    /**
     * Role activation on a hosted stack member (docs/application-roles.md structural decisions
     * 4 and 5): reads the acting-role signal the relay minted from the address's {@code _as}
     * segment, validates it against the principal's own grants — a forged signal can only
     * select among roles the caller genuinely holds, so it narrows, never widens — and swaps
     * the exchange's principal for the active view. Everything downstream (authorize, binder,
     * scopes, menus, templates, audit) reads the swapped principal unchanged. With no signal:
     * a caller holding no roles scoped to this member passes untouched (the compatibility
     * default); a browser HTML GET holding exactly one is redirected into that role's address
     * (choice of one is no choice), several to the role picker; a non-HTML request runs with
     * no application role active — deterministic, and safe because absence denies. An unheld
     * role answers TQL-SEC-4148: the picker for a browser, 403 otherwise. Emitted after the
     * fence; anywhere but a hosted member the topology bean is absent and this is a no-op.
     */
    private void activate(Exchange exchange) {
        String member = exchange.beans().lookup(
                TesseraqlProperties.STACK_MEMBER_BEAN, String.class);
        if (member == null) {
            return;
        }
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        if (principal == null) {
            return;
        }
        java.util.List<Principal.RoleGrant> held = Activation.grantsFor(principal, member);
        String encoded = exchange.getMessage().getHeader(TesseraqlProperties.ACTING_ROLE_HEADER,
                String.class);
        if (encoded != null && !encoded.isBlank()) {
            String requested = java.net.URLDecoder.decode(encoded,
                    java.nio.charset.StandardCharsets.UTF_8);
            if (held.stream().noneMatch(grant -> grant.role().equals(requested))) {
                if (wantsHtmlNavigation(exchange)) {
                    redirect(exchange, pickerLocation(exchange, member));
                    return;
                }
                throw new TqlException(Activation.WRONG_CAPACITY, "The caller does not hold"
                        + " role '" + requested + "' for application '" + member
                        + "', so it cannot act as it (activation narrows, never widens)");
            }
            exchange.setProperty(TesseraqlProperties.ACTING_ROLE, requested);
            exchange.setProperty(TesseraqlProperties.PRINCIPAL,
                    Activation.activate(principal, member, requested));
            return;
        }
        if (held.isEmpty()) {
            return;
        }
        if (wantsHtmlNavigation(exchange)) {
            if (held.size() == 1) {
                redirect(exchange, activatedLocation(exchange, held.get(0).role()));
            } else {
                redirect(exchange, pickerLocation(exchange, member));
            }
            return;
        }
        exchange.setProperty(TesseraqlProperties.PRINCIPAL,
                Activation.activate(principal, member, null));
    }

    /**
     * Whether this is a top-level browser HTML {@code GET} navigation — the only shape the
     * activation redirects answer; an htmx swap or an API caller gets a deterministic in-place
     * answer instead (the {@code ErrorResponseRenderer} login-bounce test, restated here).
     */
    private static boolean wantsHtmlNavigation(Exchange exchange) {
        if ("true".equals(exchange.getMessage().getHeader("HX-Request", String.class))) {
            return false;
        }
        Object method = exchange.getMessage().getHeader(Headers.HTTP_METHOD);
        if (method != null && !"GET".equalsIgnoreCase(String.valueOf(method))) {
            return false;
        }
        String accept = exchange.getMessage().getHeader("Accept", String.class);
        return accept != null && accept.contains("text/html");
    }

    /** The same page under the given role's activation segment, query preserved. */
    private String activatedLocation(Exchange exchange, String role) {
        String base = io.tesseraql.camel.BasePath.of(exchange.beans());
        String path = wirePath(exchange);
        String within = path.startsWith(base) ? path.substring(base.length()) : path;
        return base + "/_as/" + io.tesseraql.camel.BasePath.encodeSegment(role) + within
                + querySuffix(exchange);
    }

    /**
     * The origin role picker, carrying this member and the original wire path — origin-absolute
     * like the hosted login bounce, because the origin holds the session and the picker.
     */
    private static String pickerLocation(Exchange exchange, String member) {
        String wire = wirePath(exchange) + querySuffix(exchange);
        return "/_tesseraql/roles?app=" + java.net.URLEncoder.encode(member,
                java.nio.charset.StandardCharsets.UTF_8) + "&redirect="
                + java.net.URLEncoder.encode(wire, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The request's wire path (the member never sees the {@code _as} segment — relay-stripped). */
    private static String wirePath(Exchange exchange) {
        String path = exchange.getMessage().getHeader(Headers.HTTP_URI, String.class);
        return path == null || !path.startsWith("/") || path.startsWith("//") ? "/" : path;
    }

    private static String querySuffix(Exchange exchange) {
        String query = exchange.getMessage().getHeader(Headers.HTTP_QUERY, String.class);
        return query == null || query.isBlank() ? "" : "?" + query;
    }

    /** A 302 that ends the route here — the activation redirects, never a rendered page. */
    private static void redirect(Exchange exchange, String location) {
        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, 302);
        exchange.getMessage().setHeader("Location", location);
        exchange.getMessage().setHeader(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.getMessage().setBody("");
        exchange.setRouteStop(true);
    }

    /**
     * Validates the CSRF token of a state-changing browser request. The token comes from the
     * {@code X-CSRF-Token} header (the {@code installCsrfHeader} htmx convention) or, for a no-JS
     * plain form post, the hidden {@code _csrf} field — so a scaffolded form is protected on both
     * paths (design ch. 11.3).
     */
    private void csrf(Exchange exchange) {
        CsrfValidator validator = new CsrfValidator(
                bean(exchange, SessionStore.class, TesseraqlProperties.SESSION_STORE_BEAN));
        String header = exchange.getMessage().getHeader("X-CSRF-Token", String.class);
        String token = header != null ? header : formField(exchange, "_csrf");
        validator.validate(exchange.getMessage().getHeader("Cookie", String.class), token);
    }

    /**
     * Reads a form field from a browser post without consuming the body for the request binder:
     * platform-http parses {@code application/x-www-form-urlencoded} posts into a {@code Map} body
     * (and also exposes fields as message headers), so both are reusable reads.
     */
    private static String formField(Exchange exchange, String name) {
        Object body = exchange.getMessage().getBody();
        if (body instanceof java.util.Map<?, ?> form && form.get(name) != null) {
            return String.valueOf(form.get(name));
        }
        return exchange.getMessage().getHeader(name, String.class);
    }

    /**
     * A security bean the route's {@code auth:} mode needs, or a refusal that names it.
     *
     * <p>Its own code rather than sharing 4000 with the unsupported-operation and
     * unsupported-auth-type refusals. All three answer 500 and withhold their message from the
     * caller, which is right — none is the caller's fault, and a 401 would invite a client into
     * token-refresh retries against a server where no credential could ever succeed. That
     * leaves the code as the operator's only signal, and one code covering three unrelated
     * conditions cannot be acted on. The build-time counterpart is {@code TQL-SEC-4047}.
     */
    private <T> T bean(Exchange exchange, Class<T> type, String name) {
        T bean = exchange.beans().lookup(name, type);
        if (bean == null) {
            throw new TqlException(NOT_CONFIGURED, "Security bean '" + name + "' is not bound;"
                    + " security is not configured for auth: " + auth);
        }
        return bean;
    }
}
