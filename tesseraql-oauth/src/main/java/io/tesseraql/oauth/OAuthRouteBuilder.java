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

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final SigningKeys keys;
    private final Duration accessTokenLifetime;
    private final AuthorizeFlow flow;
    private final SessionStore sessions;
    private final TesseraqlOAuthDataProvider provider;
    private final OAuthStore store;
    private final org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeGrantHandler codeGrant;
    private final org.apache.cxf.rs.security.oauth2.grants.refresh.RefreshTokenGrantHandler refreshGrant;

    OAuthRouteBuilder(SigningKeys keys, Duration accessTokenLifetime, AuthorizeFlow flow,
            SessionStore sessions, TesseraqlOAuthDataProvider provider, OAuthStore store) {
        this.keys = keys;
        this.accessTokenLifetime = accessTokenLifetime;
        this.flow = flow;
        this.sessions = sessions;
        this.provider = provider;
        this.store = store;
        // The grant layer as decision 4 configures it: a verifier from every client, S256 the
        // only accepted transformation — plain is refused, never downgraded to.
        this.codeGrant = new org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeGrantHandler();
        codeGrant.setDataProvider(provider);
        codeGrant.setCanSupportPublicClients(true);
        codeGrant.setRequireCodeVerifier(true);
        codeGrant.setCodeVerifierTransformers(java.util.List.of(
                new org.apache.cxf.rs.security.oauth2.grants.code.DigestCodeVerifier()));
        this.refreshGrant = new org.apache.cxf.rs.security.oauth2.grants.refresh.RefreshTokenGrantHandler();
        refreshGrant.setDataProvider(provider);
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
            rest().post("/_tesseraql/oauth/token").to("direct:tql.oauth.token");
            from("direct:tql.oauth.token").routeId("system.oauth.token").process(this::token);
            rest().post("/_tesseraql/oauth/register").to("direct:tql.oauth.register");
            from("direct:tql.oauth.register").routeId("system.oauth.register")
                    .process(this::register);
        }
    }

    /**
     * RFC 7591 dynamic registration, open because gating it means not being reachable at all
     * (stack-architecture.md decision 3) — which is also why consent is mandatory and the
     * metadata stored here is display text, never something the framework vouches for. The
     * redirect URIs are stored complete and later matched exactly, as measured against Codex
     * (open question 2); registration churn — a new ephemeral port is a new registration — is
     * by design, and the last-seen stamp is how an operator finds the leftovers. The default
     * auth method is {@code none}: the measured population is native loopback clients with no
     * secret storage; a client that asks for {@code client_secret_basic} is issued a secret.
     */
    private void register(Exchange exchange) throws Exception {
        com.fasterxml.jackson.databind.JsonNode metadata;
        try {
            String body = exchange.getMessage().getBody(String.class);
            metadata = MAPPER.readTree(body == null ? "" : body);
        } catch (com.fasterxml.jackson.core.JacksonException unparsable) {
            error(exchange, 400, "invalid_client_metadata");
            return;
        }
        if (metadata == null || !metadata.isObject()) {
            error(exchange, 400, "invalid_client_metadata");
            return;
        }
        com.fasterxml.jackson.databind.JsonNode uris = metadata.path("redirect_uris");
        java.util.List<String> redirectUris = new java.util.ArrayList<>();
        if (uris.isArray()) {
            try {
                for (com.fasterxml.jackson.databind.JsonNode uri : uris) {
                    if (java.net.URI.create(uri.asText()).getScheme() == null) {
                        redirectUris.clear();
                        break;
                    }
                    redirectUris.add(uri.asText());
                }
            } catch (IllegalArgumentException malformed) {
                redirectUris.clear();
            }
        }
        if (redirectUris.isEmpty()) {
            error(exchange, 400, "invalid_redirect_uri");
            return;
        }
        String authMethod = metadata.path("token_endpoint_auth_method").asText("none");
        String clientId = "c-" + java.util.UUID.randomUUID();
        String clientSecret = "none".equals(authMethod) ? null : Tokens.newToken();
        store.saveClient(new RegisteredClient(clientId,
                clientSecret == null ? null : Tokens.sha256Hex(clientSecret),
                redirectUris,
                metadata.path("client_name").asText(null),
                metadata.toString(),
                java.time.Instant.now(),
                null));

        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("client_id", clientId);
        if (clientSecret != null) {
            answer.put("client_secret", clientSecret);
        }
        answer.put("client_id_issued_at", java.time.Instant.now().getEpochSecond());
        answer.put("token_endpoint_auth_method", "none".equals(authMethod)
                ? "none"
                : "client_secret_basic");
        answer.put("redirect_uris", redirectUris);
        if (metadata.hasNonNull("client_name")) {
            answer.put("client_name", metadata.get("client_name").asText());
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 201);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setHeader("Cache-Control", "no-store");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(answer));
    }

    /**
     * The token endpoint (docs/token-issuance.md decision 1's own compiled route): client
     * authentication first — Basic or form credentials, verified against the stored hash in
     * constant time; a public client presents its id alone — then the grant handlers CXF
     * ships, over the twelve-method provider. Every refusal is OAuth's wire vocabulary.
     */
    private void token(Exchange exchange) throws Exception {
        java.util.Map<String, String> form = formBody(exchange);
        String clientId = form.get("client_id");
        String clientSecret = form.get("client_secret");
        String authorization = exchange.getMessage().getHeader("Authorization", String.class);
        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] credentials = new String(java.util.Base64.getDecoder().decode(
                    authorization.substring(6)), StandardCharsets.UTF_8).split(":", 2);
            clientId = credentials[0];
            clientSecret = credentials.length > 1 ? credentials[1] : null;
        }
        java.util.Optional<RegisteredClient> registered = clientId == null
                ? java.util.Optional.empty()
                : store.findClient(clientId);
        if (registered.isEmpty() || !secretMatches(registered.get(), clientSecret)) {
            error(exchange, 401, "invalid_client");
            return;
        }
        org.apache.cxf.rs.security.oauth2.common.Client client = provider.getClient(clientId);
        jakarta.ws.rs.core.MultivaluedMap<String, String> params = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        form.forEach(params::putSingle);
        try {
            org.apache.cxf.rs.security.oauth2.common.ServerAccessToken token = switch (String
                    .valueOf(form.get("grant_type"))) {
                case "authorization_code" -> codeGrant.createAccessToken(client, params);
                case "refresh_token" -> refreshGrant.createAccessToken(client, params);
                default -> null;
            };
            if (token == null) {
                error(exchange, 400, form.get("grant_type") == null
                        || "authorization_code".equals(form.get("grant_type"))
                        || "refresh_token".equals(form.get("grant_type"))
                                ? "invalid_grant"
                                : "unsupported_grant_type");
                return;
            }
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("access_token", token.getTokenKey());
            body.put("token_type", "Bearer");
            body.put("expires_in", token.getExpiresIn());
            body.put("refresh_token", token.getRefreshToken());
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setHeader("Cache-Control", "no-store");
            exchange.getMessage().setBody(MAPPER.writeValueAsString(body));
        } catch (org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException refused) {
            error(exchange, 400, refused.getMessage() == null
                    ? "invalid_grant"
                    : refused.getMessage());
        }
    }

    private static boolean secretMatches(RegisteredClient registered, String presented) {
        if (registered.secretHash() == null) {
            // A public client authenticates by id alone; a secret it never had is not checked.
            return true;
        }
        if (presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                registered.secretHash().getBytes(StandardCharsets.UTF_8),
                Tokens.sha256Hex(presented).getBytes(StandardCharsets.UTF_8));
    }

    private static void error(Exchange exchange, int status, String code) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setHeader("Cache-Control", "no-store");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(
                java.util.Map.of("error", code)));
    }

    /** platform-http may pre-parse a form post into a Map body; both shapes are accepted. */
    private java.util.Map<String, String> formBody(Exchange exchange) {
        if (exchange.getMessage().getBody() instanceof java.util.Map<?, ?> parsed) {
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            parsed.forEach((key, value) -> form.put(String.valueOf(key),
                    value == null ? null : String.valueOf(value)));
            return form;
        }
        return Params.parse(exchange.getMessage().getBody(String.class));
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
