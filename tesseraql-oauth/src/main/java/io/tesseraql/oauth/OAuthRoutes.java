package io.tesseraql.oauth;

import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The authorization server's HTTP surface, growing slice by slice (docs/token-issuance.md).
 * The JWKS is public by design — the published half of the key set, identical from every
 * replica. {@code /authorize} and the consent decision are protocol endpoints
 * (decision 4): the session is the only identity question they ask, the consent <em>screen</em>
 * is the auth-ui page between them, and every answer that carries a code or an error to a
 * client rides a redirect this class builds — never a page.
 */
final class OAuthRoutes {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final SigningKeys keys;
    private final Duration accessTokenLifetime;
    private final AuthorizeFlow flow;
    private final SessionStore sessions;
    private final TesseraqlOAuthDataProvider provider;
    private final OAuthStore store;
    private final org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeGrantHandler codeGrant;
    private final org.apache.cxf.rs.security.oauth2.grants.refresh.RefreshTokenGrantHandler refreshGrant;

    OAuthRoutes(SigningKeys keys, Duration accessTokenLifetime, AuthorizeFlow flow,
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

    void install(RuntimeContext context) {
        // The error envelope every other framework surface carries, which these seven did not:
        // an unexpected failure on an OAuth endpoint left the caller holding an open connection
        // rather than answering (docs/camel-removal.md slice 2b).
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(io.tesseraql.core.error.TqlException.class,
                                new io.tesseraql.compiler.binding.ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class,
                                new io.tesseraql.compiler.binding.ErrorResponseRenderer())));
        HttpMounts.of(context).mount("GET", "/_tesseraql/oauth/jwks", "system.oauth.jwks");
        pipelines.pipeline("system.oauth.jwks").process(this::jwks);
        if (flow != null && sessions != null) {
            HttpMounts.of(context).mount("GET", "/_tesseraql/oauth/authorize",
                    "system.oauth.authorize");
            pipelines.pipeline("system.oauth.authorize")
                    .process(this::authorize);
            HttpMounts.of(context).mount("POST", "/_tesseraql/oauth/decision",
                    "system.oauth.consent");
            pipelines.pipeline("system.oauth.consent")
                    .process(this::consent);
            HttpMounts.of(context).mount("POST", "/_tesseraql/oauth/token",
                    "system.oauth.token");
            pipelines.pipeline("system.oauth.token").process(this::token);
            HttpMounts.of(context).mount("POST", "/_tesseraql/oauth/register",
                    "system.oauth.register");
            pipelines.pipeline("system.oauth.register")
                    .process(this::register);
            HttpMounts.of(context).mount("GET", "/.well-known/oauth-authorization-server",
                    "system.oauth.metadata");
            pipelines.pipeline("system.oauth.metadata")
                    .process(this::metadata);
            // RFC 9728 protected-resource metadata, path-inserted per member — the probe the
            // measured clients try first (docs/audit-hardening.md decision 2). One document
            // per member's MCP surface; the surface serves them because it is what holds the
            // member list and the origin, and the fence already owns /.well-known/*.
            for (String basePath : flow.memberAddresses().values()) {
                HttpMounts.of(context).mount("GET",
                        "/.well-known/oauth-protected-resource" + basePath + "/_tesseraql/mcp",
                        "system.oauth.resourceMetadata");
            }
            pipelines.pipeline("system.oauth.resourceMetadata")
                    .process(this::resourceMetadata);
        }
    }

    /** The document behind one member's path-inserted well-known: its resource id, and the
     * issuer as the one entry in {@code authorization_servers}. */
    private void resourceMetadata(Exchange exchange) throws Exception {
        String path = exchange.request().path();
        if (path == null || path.isBlank()) {
            path = exchange.request().uri();
        }
        String resource = flow.issuer()
                + path.substring("/.well-known/oauth-protected-resource".length());
        java.util.Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("resource", resource);
        document.put("authorization_servers", java.util.List.of(flow.issuer()));
        document.put("bearer_methods_supported", java.util.List.of("header"));
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(document));
    }

    /**
     * RFC 8414 authorization-server metadata at the bare well-known, because the issuer is the
     * stack origin with no path component (docs/token-issuance.md decision 6) — the endpoints
     * need not share the issuer's path and are listed absolute. {@code scopes_supported} is
     * deliberately absent (stack-architecture.md decision 11, measured against Codex): the
     * scope parameter is accepted and grants nothing.
     */
    private void metadata(Exchange exchange) throws Exception {
        String issuer = flow.issuer();
        java.util.Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("issuer", issuer);
        document.put("authorization_endpoint", issuer + "/_tesseraql/oauth/authorize");
        document.put("token_endpoint", issuer + "/_tesseraql/oauth/token");
        document.put("registration_endpoint", issuer + "/_tesseraql/oauth/register");
        document.put("jwks_uri", issuer + "/_tesseraql/oauth/jwks");
        document.put("response_types_supported", java.util.List.of("code"));
        document.put("grant_types_supported",
                java.util.List.of("authorization_code", "refresh_token"));
        document.put("code_challenge_methods_supported", java.util.List.of("S256"));
        document.put("token_endpoint_auth_methods_supported",
                java.util.List.of("none", "client_secret_basic"));
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(document));
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
        exchange.response().status(201);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json");
        exchange.response().header("Cache-Control", "no-store");
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
        String authorization = exchange.request().header("Authorization");
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
            exchange.response().status(200);
            exchange.response().header(Headers.CONTENT_TYPE, "application/json");
            exchange.response().header("Cache-Control", "no-store");
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
        exchange.response().status(status);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json");
        exchange.response().header("Cache-Control", "no-store");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(
                java.util.Map.of("error", code)));
    }

    /** The form has one representation (docs/vertx-native.md decision 2): the edge parsed it. */
    private java.util.Map<String, String> formBody(Exchange exchange) {
        if (!exchange.request().formFields().isEmpty()) {
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            exchange.request().formFields()
                    .forEach((name, values) -> form.put(name, values.get(0)));
            return form;
        }
        return Params.parse(exchange.getMessage().getBody(String.class));
    }

    private void jwks(Exchange exchange) {
        exchange.response().header(Headers.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(JwksDocuments.render(keys.published(accessTokenLifetime)));
    }

    /**
     * The protocol GET: no session sends the caller through the existing login bounce and back;
     * a recorded consent (or a refusal the client may learn about) answers with the redirect;
     * everything else lands on the consent page with the request echoed in the query.
     */
    private void authorize(Exchange exchange) {
        String query = exchange.request().query();
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
        java.util.Map<String, String> form = formBody(exchange);
        String expected = session.csrfToken();
        if (expected == null || !expected.equals(form.get("_csrf"))) {
            exchange.response().status(403);
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
        String cookie = exchange.request().header("Cookie");
        if (cookie == null) {
            return null;
        }
        return sessions.session(sessions.sessionIdFromCookie(cookie));
    }

    private static void redirect(Exchange exchange, int status, String location) {
        exchange.response().status(status);
        exchange.response().header("Location", location);
        exchange.getMessage().setBody("");
    }
}
