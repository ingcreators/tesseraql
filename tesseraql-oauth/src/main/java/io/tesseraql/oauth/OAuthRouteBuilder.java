package io.tesseraql.oauth;

import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The authorization server's HTTP surface, growing slice by slice (docs/token-issuance.md).
 * The JWKS is public by design — the published half of the key set, identical from every
 * replica. {@code /authorize} and the consent decision are protocol endpoints
 * (decision 4): the session is the only identity question they ask, the consent <em>screen</em>
 * is the auth-ui page between them, and every answer that carries a code or an error to a
 * client rides a redirect this class builds — never a page.
 */
final class OAuthRouteBuilder extends RouteBuilder {

    private final SigningKeys keys;
    private final Duration accessTokenLifetime;
    private final AuthorizeFlow flow;
    private final SessionStore sessions;

    OAuthRouteBuilder(SigningKeys keys, Duration accessTokenLifetime, AuthorizeFlow flow,
            SessionStore sessions) {
        this.keys = keys;
        this.accessTokenLifetime = accessTokenLifetime;
        this.flow = flow;
        this.sessions = sessions;
    }

    @Override
    public void configure() {
        rest().get("/_tesseraql/oauth/jwks").to("direct:tql.oauth.jwks");
        from("direct:tql.oauth.jwks").routeId("system.oauth.jwks").process(this::jwks);
        if (flow != null && sessions != null) {
            rest().get("/_tesseraql/oauth/authorize").to("direct:tql.oauth.authorize");
            from("direct:tql.oauth.authorize").routeId("system.oauth.authorize")
                    .process(this::authorize);
            rest().post("/_tesseraql/oauth/decision").to("direct:tql.oauth.consent");
            from("direct:tql.oauth.consent").routeId("system.oauth.consent")
                    .process(this::consent);
        }
    }

    private void jwks(Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(JwksDocuments.render(keys.published(accessTokenLifetime)));
    }

    /**
     * The protocol GET: no session sends the caller through the existing login bounce and back;
     * a recorded consent (or a refusal the client may learn about) answers with the redirect;
     * everything else lands on the consent page with the request echoed in the query.
     */
    private void authorize(Exchange exchange) {
        String query = exchange.getMessage().getHeader(Exchange.HTTP_QUERY, String.class);
        SessionStore.Session session = session(exchange);
        if (session == null) {
            redirect(exchange, 302, "/_tesseraql/login?redirect="
                    + URLEncoder.encode("/_tesseraql/oauth/authorize"
                            + (query == null || query.isBlank() ? "" : "?" + query),
                            StandardCharsets.UTF_8));
            return;
        }
        Principal principal = session.principal();
        AuthorizeFlow.Outcome outcome = flow.authorize(Params.parse(query),
                principal.subject(), principal.loginId(), principal.roleGrants());
        answer(exchange, outcome, query);
    }

    /**
     * The consent decision: CSRF-guarded against the session the same way the token exchange
     * is, validated afresh from the form, and answered with the authorization response
     * redirect — the code, or the refusal the client is allowed to learn about.
     */
    private void consent(Exchange exchange) {
        SessionStore.Session session = session(exchange);
        if (session == null) {
            redirect(exchange, 302, "/_tesseraql/login");
            return;
        }
        // platform-http may pre-parse a browser form post into a Map body; use it directly.
        java.util.Map<String, String> form;
        if (exchange.getMessage().getBody() instanceof java.util.Map<?, ?> parsed) {
            form = new java.util.LinkedHashMap<>();
            parsed.forEach((key, value) -> form.put(String.valueOf(key),
                    value == null ? null : String.valueOf(value)));
        } else {
            form = Params.parse(exchange.getMessage().getBody(String.class));
        }
        String expected = session.csrfToken();
        if (expected == null || !expected.equals(form.get("_csrf"))) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 403);
            exchange.getMessage().setBody("");
            return;
        }
        Principal principal = session.principal();
        AuthorizeFlow.Outcome outcome = flow.approve(form,
                principal.subject(), principal.loginId(), principal.roleGrants());
        answer(exchange, outcome, null);
    }

    private void answer(Exchange exchange, AuthorizeFlow.Outcome outcome, String echoQuery) {
        if (outcome.redirect() != null) {
            redirect(exchange, 303, outcome.redirect());
        } else if (outcome.pageError() != null) {
            redirect(exchange, 302, "/_tesseraql/oauth/consent?error=" + URLEncoder.encode(
                    outcome.pageError(), StandardCharsets.UTF_8));
        } else {
            redirect(exchange, 302, "/_tesseraql/oauth/consent"
                    + (echoQuery == null || echoQuery.isBlank() ? "" : "?" + echoQuery));
        }
    }

    private SessionStore.Session session(Exchange exchange) {
        String cookie = exchange.getMessage().getHeader("Cookie", String.class);
        if (cookie == null) {
            return null;
        }
        return sessions.session(sessions.sessionIdFromCookie(cookie));
    }

    private static void redirect(Exchange exchange, int status, String location) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader("Location", location);
        exchange.getMessage().setBody("");
    }
}
