package io.tesseraql.camel.auth;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.apikey.ApiKeyAuthenticator;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.mtls.MtlsAuthenticator;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.BrowserAuthenticator;
import io.tesseraql.security.session.CsrfValidator;
import io.tesseraql.security.session.SessionStore;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

/**
 * Performs the {@code authenticate} and {@code authorize} operations for the {@code tesseraql-auth}
 * component (design ch. 9.2, 7.2). The {@link PolicyEngine} and {@link JwtAuthenticator} are looked
 * up from the Camel registry, where the runtime binds them from the security configuration.
 */
public class TesseraqlAuthProducer extends DefaultProducer {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.SEC, 4000);

    /** TQL-SEC-4001: the authenticator this route needs is not configured, so nothing can pass. */
    private static final TqlErrorCode NOT_CONFIGURED = new TqlErrorCode(TqlDomain.SEC, 4001);

    private final TesseraqlAuthEndpoint endpoint;

    public TesseraqlAuthProducer(TesseraqlAuthEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public void process(Exchange exchange) {
        String operation = endpoint.getOperation();
        io.tesseraql.core.telemetry.Span span = io.tesseraql.camel.TesseraqlTracing.tracer(exchange)
                .start("tesseraql.security." + operation,
                        io.tesseraql.camel.TesseraqlTracing.parent(exchange))
                .attribute("operation", operation);
        if (endpoint.getAuth() != null) {
            span.attribute("auth", endpoint.getAuth());
        }
        if (endpoint.getPolicy() != null) {
            span.attribute("policy", endpoint.getPolicy());
        }
        try {
            switch (operation) {
                case "authenticate" -> authenticate(exchange);
                case "authorize" -> authorize(exchange);
                case "csrf" -> csrf(exchange);
                case "rotate" -> rotate(exchange);
                case "fence" -> fence(exchange);
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
        SessionStore sessions = endpoint.getCamelContext().getRegistry().lookupByNameAndType(
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
        Principal principal = switch (endpoint.getAuth()) {
            case "bearer" ->
                bean(JwtAuthenticator.class, TesseraqlProperties.JWT_AUTHENTICATOR_BEAN)
                        .authenticate(
                                exchange.getMessage().getHeader("Authorization", String.class));
            case "api-key" -> apiKeyAuthenticate(exchange);
            case "mtls" -> mtlsAuthenticate(exchange);
            case "browser" -> browserAuthenticate(exchange);
            default ->
                throw new TqlException(UNSUPPORTED, "Unsupported auth type: " + endpoint.getAuth());
        };
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principal);
    }

    /**
     * Resolves a service caller's API key, presented either in the configured key header (default
     * {@code X-API-Key}) or as {@code Authorization: ApiKey <key>} for gateways that forward only
     * the {@code Authorization} header (design ch. 11.1).
     */
    private Principal apiKeyAuthenticate(Exchange exchange) {
        ApiKeyAuthenticator authenticator = bean(ApiKeyAuthenticator.class,
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
        MtlsAuthenticator authenticator = bean(MtlsAuthenticator.class,
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
        SessionStore sessions = bean(SessionStore.class, TesseraqlProperties.SESSION_STORE_BEAN);
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
        String member = endpoint.getCamelContext().getRegistry().lookupByNameAndType(
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

    private void authorize(Exchange exchange) {
        PolicyEngine engine = bean(PolicyEngine.class, TesseraqlProperties.POLICY_ENGINE_BEAN);
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        engine.authorize(endpoint.getPolicy(), principal);
    }

    /**
     * Validates the CSRF token of a state-changing browser request. The token comes from the
     * {@code X-CSRF-Token} header (the {@code installCsrfHeader} htmx convention) or, for a no-JS
     * plain form post, the hidden {@code _csrf} field — so a scaffolded form is protected on both
     * paths (design ch. 11.3).
     */
    private void csrf(Exchange exchange) {
        CsrfValidator validator = new CsrfValidator(
                bean(SessionStore.class, TesseraqlProperties.SESSION_STORE_BEAN));
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
    private <T> T bean(Class<T> type, String name) {
        T bean = endpoint.getCamelContext().getRegistry().lookupByNameAndType(name, type);
        if (bean == null) {
            throw new TqlException(NOT_CONFIGURED, "Security bean '" + name + "' is not bound;"
                    + " security is not configured for auth: " + endpoint.getAuth());
        }
        return bean;
    }
}
