package io.tesseraql.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.federation.FederationErrors;
import io.tesseraql.security.session.LoginRedirects;
import io.tesseraql.security.session.SessionStore;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The OIDC relying-party web endpoints under {@code /_tesseraql/oidc} (design ch. 10.14, roadmap
 * Phase 25): {@code GET /login} starts the authorization-code + PKCE flow, {@code GET /callback}
 * exchanges the code and issues a session, and {@code GET /logout} ends it. A failure returns 401
 * without leaking the code, token, or secret. Mirrors the SAML SP route builder.
 */
final class OidcRouteBuilder extends RouteBuilder {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(OidcRouteBuilder.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final OidcConfig config;
    private final OidcDiscovery discovery;
    private final OidcStateStore stateStore;
    private final OidcHttp http;
    private final SessionStore sessions;
    private final OidcUserLinker linker;
    private final AtomicReference<OidcTokenValidator> validatorRef = new AtomicReference<>();

    /** Short-lived cookie carrying the sanitized post-login {@code next} from /login to /callback. */
    private static final String NEXT_COOKIE = "tql_oidc_next";

    OidcRouteBuilder(OidcConfig config, OidcDiscovery discovery, OidcStateStore stateStore,
            OidcHttp http, SessionStore sessions, OidcUserLinker linker,
            io.tesseraql.security.throttle.CredentialThrottle throttle) {
        this.config = config;
        this.discovery = discovery;
        this.stateStore = stateStore;
        this.http = http;
        this.sessions = sessions;
        this.linker = linker;
        this.throttle = throttle;
    }

    private final io.tesseraql.security.throttle.CredentialThrottle throttle;

    /**
     * Address-keyed insurance against callback garbage (docs/credential-throttle.md):
     * the IdP owns the credential behind this flow, so only failures count here - a
     * successful round-trip is never throttled.
     */
    private void guardCallback(Exchange exchange) {
        if (throttle == null) {
            return;
        }
        String address = io.tesseraql.security.session.SessionStore.ClientInfo.of(null,
                exchange.getMessage().getHeader("X-Forwarded-For", String.class),
                exchange.getMessage().getHeader("CamelVertxPlatformHttpRemoteAddress",
                        String.class))
                .remoteAddr();
        if (throttle.retryAfter("oidc", null, address).isPresent()) {
            throw new OidcException("Too many failed callbacks; retry later");
        }
        exchange.setProperty("tqlThrottleAddress", address);
    }

    private void recordCallbackFailure(Exchange exchange) {
        if (throttle == null) {
            return;
        }
        Object address = exchange.getProperty("tqlThrottleAddress");
        throttle.recordFailure(null, address == null ? null : String.valueOf(address));
    }

    @Override
    public void configure() {
        onException(OidcException.class).handled(true).process(this::unauthorized);
        onException(Exception.class).handled(true).process(this::badRequest);

        rest().get("/_tesseraql/oidc/login").to("direct:tql.oidc.login");
        from("direct:tql.oidc.login").routeId("system.oidc.login").process(this::login);

        rest().get("/_tesseraql/oidc/callback").to("direct:tql.oidc.callback");
        from("direct:tql.oidc.callback").routeId("system.oidc.callback").process(this::callback);

        rest().get("/_tesseraql/oidc/logout").to("direct:tql.oidc.logout");
        from("direct:tql.oidc.logout").routeId("system.oidc.logout").process(this::logout);
    }

    /** Starts the flow: record state/nonce/PKCE and redirect to the OP authorization endpoint. */
    private void login(Exchange exchange) {
        OidcMetadata metadata = discovery.metadata();
        String state = Pkce.token();
        String nonce = Pkce.token();
        String verifier = Pkce.verifier();
        stateStore.store(state, nonce, verifier);

        // Carry the (sanitized, same-origin) post-login target across the IdP round-trip in a
        // short-lived HttpOnly cookie, so the callback can return the user to the page they wanted.
        String next = LoginRedirects.sanitize(header(exchange, "next"), null);
        if (next != null) {
            // Scoped to the OIDC endpoints, so this one follows the base path they are
            // mounted under rather than the session cookie's (docs/base-path.md).
            exchange.getMessage().setHeader("Set-Cookie", NEXT_COOKIE + "=" + encode(next)
                    + "; Path=" + io.tesseraql.camel.BasePath.url(exchange, "/_tesseraql/oidc")
                    + "; HttpOnly; SameSite=Lax; Max-Age=600");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", config.clientId());
        params.put("redirect_uri", config.redirectUri());
        params.put("scope", String.join(" ", config.scopes()));
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", Pkce.challenge(verifier));
        params.put("code_challenge_method", "S256");

        URI endpoint = metadata.authorizationEndpoint();
        String separator = endpoint.getQuery() == null ? "?" : "&";
        redirect(exchange, endpoint + separator + queryString(params));
    }

    /** Completes the flow: validate state, exchange the code, validate the ID token, open a session. */
    private void callback(Exchange exchange) {
        guardCallback(exchange);
        String state = header(exchange, "state");
        String error = header(exchange, "error");
        if (error != null) {
            // Consume the state even on the OP error path, then fail: prevents state fixation.
            stateStore.consume(state);
            recordCallbackFailure(exchange);
            throw new OidcException("OIDC provider returned an error: " + error);
        }
        String code = header(exchange, "code");
        if (code == null || state == null) {
            recordCallbackFailure(exchange);
            throw new OidcException("Missing authorization code or state");
        }
        OidcStateStore.Pending pending = stateStore.consume(state)
                .orElseThrow(() -> {
                    recordCallbackFailure(exchange);
                    return new OidcException("Unknown or replayed state");
                });

        OidcMetadata metadata = discovery.metadata();
        String idToken = exchangeCode(metadata, code, pending.codeVerifier());
        Principal validated = validator(metadata).validate(idToken, pending.nonce());
        Principal principal = link(validated, metadata.issuer());

        // The deployment's sign-in allow-list applies however the session was established
        // (docs/access-governance.md structural decision 8, layer A).
        String sessionId = sessions.create(principal,
                io.tesseraql.camel.auth.SignInAdmission.admitted(exchange));
        exchange.getMessage().setHeader("Set-Cookie",
                io.tesseraql.security.session.SessionCookie.issue(sessions.cookieName(),
                        sessionId, io.tesseraql.camel.CookiePath.of(exchange)));
        redirect(exchange, postLoginTarget(exchange));
    }

    /** The post-login target: the sanitized {@code next} carried since /login, else the default. */
    private String postLoginTarget(Exchange exchange) {
        String cookie = cookieValue(header(exchange, "Cookie"), NEXT_COOKIE);
        String next = cookie == null ? null : URLDecoder.decode(cookie, StandardCharsets.UTF_8);
        return LoginRedirects.sanitize(next, config.postLoginUrl());
    }

    /** Ends the local session and, when the OP advertises one, redirects to its logout endpoint. */
    private void logout(Exchange exchange) {
        String sessionId = cookieValue(header(exchange, "Cookie"), sessions.cookieName());
        sessions.invalidate(sessionId);
        exchange.getMessage().setHeader("Set-Cookie",
                io.tesseraql.security.session.SessionCookie.expire(sessions.cookieName(),
                        io.tesseraql.camel.CookiePath.of(exchange)));
        try {
            URI endSession = discovery.metadata().endSessionEndpoint();
            if (endSession != null) {
                redirect(exchange, endSession.toString());
                return;
            }
        } catch (RuntimeException ignored) {
            // Logout must always clear the local session, even if the OP is unreachable.
        }
        ok(exchange);
    }

    private String exchangeCode(OidcMetadata metadata, String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", config.redirectUri());
        form.put("code_verifier", codeVerifier);
        String authorization = null;
        if (config.confidential()) {
            // client_secret_basic (RFC 6749 §2.3.1): base64(urlencode(id):urlencode(secret)).
            String credentials = encode(config.clientId()) + ":" + encode(config.clientSecret());
            authorization = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        } else {
            form.put("client_id", config.clientId());
        }
        try {
            String body = http.postForm(metadata.tokenEndpoint(), form, authorization);
            String idToken = mapper.readTree(body).path("id_token").asText(null);
            if (idToken == null || idToken.isBlank()) {
                throw new OidcException("Token response did not contain an id_token");
            }
            return idToken;
        } catch (OidcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OidcException("Token response was not valid JSON");
        }
    }

    private Principal link(Principal validated, String issuer) {
        if (linker == null) {
            return validated;
        }
        Object email = validated.claims().get("email");
        // The immutable link key is iss + sub (docs/application-roles.md structural decision
        // 3); the login claim stays what the principal resolves and re-syncs by.
        return linker.resolve(new io.tesseraql.identity.FederatedIdentities.FederatedLogin(
                issuer, validated.subject(), validated.loginId(), validated.displayName(),
                email == null ? null : String.valueOf(email), validated.tenantId(),
                mappedAttributes(validated)));
    }

    /**
     * The declared attribute capture: each mapped ID-token claim under its store name, an absent
     * one mapped to null so the store copy is deleted (re-sync converges, never accretes).
     */
    private Map<String, String> mappedAttributes(Principal validated) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : config.attributes().entrySet()) {
            Object value = validated.claims().get(entry.getKey());
            values.put(entry.getValue(), value == null ? null : String.valueOf(value));
        }
        return values;
    }

    private OidcTokenValidator validator(OidcMetadata metadata) {
        return validatorRef.updateAndGet(
                existing -> existing != null ? existing : new OidcTokenValidator(metadata, config));
    }

    /**
     * The provider's authorization or end-session URL, or a local post-login target. An absolute
     * URL is the provider's and is left alone; a local target is base-relative and acquires the
     * application's prefix here, like every other URL leaving the runtime (docs/base-path.md
     * decision 7).
     */
    private void redirect(Exchange exchange, String location) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 302);
        exchange.getMessage().setHeader("Location",
                io.tesseraql.camel.BasePath.url(exchange, location));
        exchange.getMessage().setBody(null);
    }

    private void ok(Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody("{\"ok\":true}");
    }

    private void unauthorized(Exchange exchange) {
        respondError(exchange, FederationErrors.UNAUTHENTICATED, "OIDC authentication failed");
    }

    /**
     * The catch-all, which used to answer 400 for everything.
     *
     * <p>{@code onException(Exception.class)} covers a malformed callback and a broken IdP alike,
     * and collapsing both into 400 told an operator their request was wrong when the truth was
     * that the server failed. A {@link TqlException} keeps its own code and status; anything else
     * is a server-side failure and says so.
     */
    private void badRequest(Exchange exchange) {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        if (cause instanceof TqlException tql) {
            respondError(exchange, tql.code(), "OIDC request refused");
            return;
        }
        LOG.warn("OIDC request failed", cause);
        respondError(exchange, FederationErrors.FAILED, "OIDC request failed");
    }

    /** The framework envelope, so a federation error reads like every other error. */
    private void respondError(Exchange exchange, TqlErrorCode code, String message) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                ErrorResponseRenderer.httpStatus(code));
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(FederationErrors.body(code, message));
    }

    private static String header(Exchange exchange, String name) {
        return exchange.getMessage().getHeader(name, String.class);
    }

    private static String queryString(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(key)).append('=').append(encode(value));
        });
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }
}
