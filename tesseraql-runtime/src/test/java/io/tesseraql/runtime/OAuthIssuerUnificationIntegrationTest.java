package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.oauth.SigningKeys;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One issuer per stack, end to end (docs/token-issuance.md decision 9): the stack file enables
 * the authorization server and declares no secret; the session exchange at the origin mints
 * RS256 with the database-held key; and a member — whose validation block was derived, not
 * declared — accepts the token after fetching the JWKS through the gateway. Two doors, one
 * issuer, zero per-member jwt configuration.
 */
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class OAuthIssuerUnificationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static MultiAppGateway gateway;
    static Path installRoot;
    static Path work;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        work = Files.createTempDirectory("tesseraql-issuer-unification-work");
        installRoot = Files.createTempDirectory("tesseraql-issuer-unification-it");
        new AppInstaller().install(packaged(appHome("shop", "s1")), installRoot);
        // A member named in Japanese: addressed on the wire as /%E5%8F%97%E6%B3%A8, its MCP
        // resource a URI spelled the same way (docs/audit-low-leads.md, unfiled 48).
        new AppInstaller().install(packaged(appHome("受注", "s2")), installRoot);
        // The origin must be declared before the gateway binds, so the port is picked first —
        // and picked again if it is taken in the meantime (docs/host-development.md decision 8).
        gateway = PickedPort.start(picked -> {
            port = picked;
            Files.writeString(installRoot.resolve(
                    io.tesseraql.operations.app.StackSettings.FILE_NAME),
                    """
                            externalOrigin: http://localhost:%d
                            framework:
                              datasource:
                                jdbcUrl: %s
                                username: %s
                                password: %s
                            security:
                              oauth:
                                enabled: true
                              token:
                                enabled: true
                            """.formatted(picked, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                            POSTGRES.getPassword()));
            return MultiAppGateway.start(installRoot, picked);
        });
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        deleteRecursively(installRoot);
        deleteRecursively(work);
    }

    @Test
    void theExchangeMintsRs256WithTheStacksKey() throws Exception {
        String token = acquireBearer("alice");
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asString()).isEqualTo("RS256");
        assertThat(header.get("kid").asString()).isEqualTo(SigningKeys.INITIAL_KID);

        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("iss").asString()).isEqualTo("http://localhost:" + port);
        assertThat(payload.get("sub").asString()).isEqualTo("u-alice");
        assertThat(payload.get("roles")).isNotNull();
    }

    @Test
    void aMemberValidatesTheStackTokenThroughTheDerivedBlock() throws Exception {
        String token = acquireBearer("alice");

        HttpResponse<String> accepted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/shop/api/secure"))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(accepted.body()).get("data").get(0).get("name").asString())
                .isEqualTo("s1");
    }

    @Test
    void aMemberScopedTokenCarriesTheMembersAddressAndWorksThere() throws Exception {
        HttpResponse<String> exchanged = exchange("alice", "{\"appName\":\"shop\"}");
        assertThat(exchanged.statusCode()).as(exchanged.body()).isEqualTo(200);
        String token = MAPPER.readTree(exchanged.body()).path("token").asString();

        JsonNode payload = MAPPER.readTree(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload.get("aud").asString())
                .isEqualTo("http://localhost:" + port + "/shop");

        HttpResponse<String> accepted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/shop/api/secure"))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    }

    @Test
    void anUnknownMemberInATokenRequestIsTheCallersError() throws Exception {
        HttpResponse<String> refused = exchange("alice", "{\"appName\":\"nope\"}");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("TQL-OAUTH-3003");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void malformedBasicCredentialsAreInvalidClientNotAServerError() throws Exception {
        // RFC 6749 §5.2: credentials that cannot be read are a client that failed to
        // authenticate. The base64 decode used to throw into the generic envelope — a 500
        // claiming the server failed when the caller's header was the problem.
        HttpResponse<String> refused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic %%%not-base64%%%")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).contains("invalid_client");
    }

    @Test
    void withoutASessionAuthorizeBouncesThroughLogin() throws Exception {
        HttpResponse<String> bounced = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + authorizeQuery())).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(bounced.statusCode()).isEqualTo(302);
        assertThat(bounced.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/login?redirect=");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void theAuthorizeFlowIssuesACodeThroughConsent() throws Exception {
        seedClient();
        String[] session = signIn("alice");
        String cookie = session[0];
        String csrf = session[1];

        // The protocol GET owes the consent screen on first contact.
        HttpResponse<String> toConsent = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + authorizeQuery()))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(toConsent.statusCode()).isEqualTo(302);
        String consentPage = toConsent.headers().firstValue("Location").orElseThrow();
        assertThat(consentPage).startsWith("/_tesseraql/oauth/consent?");

        // The page renders the client's (escaped) name and the consent form.
        HttpResponse<String> page = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + consentPage))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("Codex &lt;CLI&gt;").contains("decision");

        // Approval answers with the authorization response: code, state, RFC 9207 iss.
        String form = "_csrf=" + enc(csrf) + "&client_id=codex&redirect_uri="
                + enc("http://127.0.0.1:49681/callback/x") + "&state=xyz"
                + "&code_challenge=" + enc(challenge(VERIFIER))
                + "&resource=" + enc("http://localhost:" + port + "/shop")
                + "&decision=approve";
        HttpResponse<String> approved = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/decision"))
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(303);
        String callback = approved.headers().firstValue("Location").orElseThrow();
        assertThat(callback).startsWith("http://127.0.0.1:49681/callback/x?")
                .contains("code=").contains("state=xyz").contains("iss=");

        // The code redeems at /token with the PKCE verifier — the whole OAuth chain, and the
        // access token works at the member like any stack token.
        String code = callback.replaceAll(".*[?&]code=([^&]+).*", "$1");
        HttpResponse<String> minted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&client_id=codex&code=" + enc(code)
                                + "&redirect_uri=" + enc("http://127.0.0.1:49681/callback/x")
                                + "&code_verifier=" + enc(VERIFIER)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(minted.statusCode()).as(minted.body()).isEqualTo(200);
        JsonNode tokens = MAPPER.readTree(minted.body());
        String access = tokens.get("access_token").asString();
        String refresh = tokens.get("refresh_token").asString();

        JsonNode payload = MAPPER.readTree(
                Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertThat(payload.get("aud").asString())
                .isEqualTo("http://localhost:" + port + "/shop");
        assertThat(payload.get("roles").toString()).contains("staff");

        HttpResponse<String> accepted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/shop/api/secure"))
                .header("Authorization", "Bearer " + access)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);

        // A code is single-use: redeeming it again is invalid_grant.
        HttpResponse<String> replayed = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&client_id=codex&code=" + enc(code)
                                + "&redirect_uri=" + enc("http://127.0.0.1:49681/callback/x")
                                + "&code_verifier=" + enc(VERIFIER)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(replayed.statusCode()).isEqualTo(400);

        // Refresh rotates; the spent token presented again retires the chain.
        HttpResponse<String> refreshed = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=refresh_token&client_id=codex&refresh_token="
                                + enc(refresh)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refreshed.statusCode()).as(refreshed.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(refreshed.body()).get("refresh_token").asString())
                .isNotEqualTo(refresh);

        HttpResponse<String> reused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=refresh_token&client_id=codex&refresh_token="
                                + enc(refresh)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reused.statusCode()).isEqualTo(400);

        // The live end of the chain, for the revocation test downstream.
        liveRefresh = MAPPER.readTree(refreshed.body()).get("refresh_token").asString();
    }

    /** The rotated chain's live end after the token test — what revocation must kill. */
    static String liveRefresh;

    @Test
    @org.junit.jupiter.api.Order(5)
    void theAccountPageListsTheConnectionAndRevocationEndsIt() throws Exception {
        String[] session = signIn("alice");
        String cookie = session[0];
        String csrf = session[1];

        // The page lists the consent the flow recorded — client name escaped, resource named.
        HttpResponse<String> page = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/account/connections"))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("Codex &lt;CLI&gt;").contains("/shop");

        // Revocation deletes the consent and its refresh chains together.
        HttpResponse<String> revoked = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port
                        + "/_tesseraql/account/connections/revoke"))
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + enc(csrf)
                        + "&clientId=codex&resource="
                        + enc("http://localhost:" + port + "/shop")))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(303);

        // The chain's live end died with the consent...
        HttpResponse<String> refreshRefused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=refresh_token&client_id=codex&refresh_token="
                                + enc(liveRefresh)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refreshRefused.statusCode()).isEqualTo(400);

        // ...and coming back is a re-authorization: the consent screen is owed again.
        HttpResponse<String> again = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + authorizeQuery()))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(again.statusCode()).isEqualTo(302);
        assertThat(again.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/oauth/consent?");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aRecordedConsentSkipsTheScreen() throws Exception {
        String cookie = signIn("alice")[0];

        HttpResponse<String> immediate = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + authorizeQuery()))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(immediate.statusCode()).isEqualTo(303);
        assertThat(immediate.headers().firstValue("Location").orElse(""))
                .startsWith("http://127.0.0.1:49681/callback/x?").contains("code=");
    }

    private static final String VERIFIER = "correct-horse-battery-staple-correct-horse-battery";

    private static String authorizeQuery() {
        return "/_tesseraql/oauth/authorize?client_id=codex&response_type=code"
                + "&redirect_uri=" + enc("http://127.0.0.1:49681/callback/x")
                + "&state=xyz&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge(VERIFIER))
                + "&resource=" + enc("http://localhost:" + port + "/shop");
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registered the way slice 6's endpoint will register: straight into the store. The name
     * carries markup on purpose — the consent page must escape it, never trust it. */
    private static void seedClient() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        new io.tesseraql.oauth.JdbcOAuthStore(dataSource).saveClient(
                new io.tesseraql.oauth.RegisteredClient("codex", null,
                        java.util.List.of("http://127.0.0.1:49681/callback/x"),
                        "Codex <CLI>", null, java.time.Instant.now(), null));
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String[] signIn(String loginId) throws Exception {
        HttpResponse<String> login = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"" + loginId + "\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        return new String[]{setCookie.substring(0, setCookie.indexOf(';')),
                MAPPER.readTree(login.body()).path("csrfToken").asString()};
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void dynamicRegistrationIssuesAClientThatAuthorizes() throws Exception {
        // The Codex shape (open question 2): the complete callback with an ephemeral port and
        // callback id, no auth method — a public client.
        HttpResponse<String> registered = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"redirect_uris\":[\"http://127.0.0.1:50231/callback/gSuWNlcO\"],"
                                + "\"client_name\":\"Codex CLI\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        JsonNode issued = MAPPER.readTree(registered.body());
        String clientId = issued.get("client_id").asString();
        assertThat(issued.get("token_endpoint_auth_method").asString()).isEqualTo("none");
        assertThat(issued.has("client_secret")).isFalse();

        // The registration is live: the registered client walks into the authorize flow and
        // is owed the consent screen (a new client has no recorded consent).
        String cookie = signIn("alice")[0];
        HttpResponse<String> toConsent = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port
                        + "/_tesseraql/oauth/authorize?client_id=" + enc(clientId)
                        + "&response_type=code&state=reg"
                        + "&redirect_uri=" + enc("http://127.0.0.1:50231/callback/gSuWNlcO")
                        + "&code_challenge_method=S256&code_challenge="
                        + enc(challenge(VERIFIER))
                        + "&resource=" + enc("http://localhost:" + port + "/shop")))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(toConsent.statusCode()).isEqualTo(302);
        assertThat(toConsent.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/oauth/consent?");
    }

    @Test
    void theMetadataSitsAtTheBareWellKnownAndAdvertisesNoScopes() throws Exception {
        HttpResponse<String> metadata = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port
                        + "/.well-known/oauth-authorization-server"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
        JsonNode document = MAPPER.readTree(metadata.body());
        String issuer = "http://localhost:" + port;
        assertThat(document.get("issuer").asString()).isEqualTo(issuer);
        assertThat(document.get("authorization_endpoint").asString())
                .isEqualTo(issuer + "/_tesseraql/oauth/authorize");
        assertThat(document.get("token_endpoint").asString())
                .isEqualTo(issuer + "/_tesseraql/oauth/token");
        assertThat(document.get("registration_endpoint").asString())
                .isEqualTo(issuer + "/_tesseraql/oauth/register");
        assertThat(document.get("jwks_uri").asString())
                .isEqualTo(issuer + "/_tesseraql/oauth/jwks");
        assertThat(document.get("code_challenge_methods_supported").toString())
                .contains("S256");
        // Deliberately absent (stack-architecture.md decision 11, measured against Codex).
        assertThat(document.has("scopes_supported")).isFalse();
    }

    @Test
    void aRegistrationWithoutACompleteCallbackIsRefused() throws Exception {
        HttpResponse<String> refused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"client_name\":\"no-uris\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("invalid_redirect_uri");
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void theMcpSurfaceIsGatedDiscoverableAndAudienceBound() throws Exception {
        String mcpResource = "http://localhost:" + port + "/shop/_tesseraql/mcp";

        // The path-inserted well-known — the probe the measured clients try first.
        HttpResponse<String> metadata = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port
                        + "/.well-known/oauth-protected-resource/shop/_tesseraql/mcp"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
        JsonNode document = MAPPER.readTree(metadata.body());
        assertThat(document.get("resource").asString()).isEqualTo(mcpResource);
        assertThat(document.get("authorization_servers").get(0).asString())
                .isEqualTo("http://localhost:" + port);

        // No token: 401, and the challenge names where the metadata lives.
        HttpResponse<String> challenged = mcp(mcpResource, null);
        assertThat(challenged.statusCode()).isEqualTo(401);
        assertThat(challenged.headers().firstValue("WWW-Authenticate").orElse(""))
                .contains("resource_metadata=")
                .contains("/shop/_tesseraql/mcp");

        // A member-API token is not the MCP audience: the gate refuses it.
        HttpResponse<String> memberToken = exchange("alice", "{\"appName\":\"shop\"}");
        String wrongAudience = MAPPER.readTree(memberToken.body()).path("token").asString();
        assertThat(mcp(mcpResource, wrongAudience).statusCode()).isEqualTo(401);

        // A token granted FOR the MCP resource opens it: the whole chain, once more, with the
        // resource below the member's address (a new resource means a new consent).
        String[] session = signIn("alice");
        String form = "_csrf=" + enc(session[1]) + "&client_id=codex&redirect_uri="
                + enc("http://127.0.0.1:49681/callback/x") + "&state=mcp"
                + "&code_challenge=" + enc(challenge(VERIFIER))
                + "&resource=" + enc(mcpResource) + "&decision=approve";
        HttpResponse<String> approved = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/decision"))
                .header("Cookie", session[0])
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(303);
        String code = approved.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*[?&]code=([^&]+).*", "$1");
        HttpResponse<String> minted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&client_id=codex&code=" + enc(code)
                                + "&redirect_uri=" + enc("http://127.0.0.1:49681/callback/x")
                                + "&code_verifier=" + enc(VERIFIER)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(minted.statusCode()).as(minted.body()).isEqualTo(200);
        String access = MAPPER.readTree(minted.body()).get("access_token").asString();

        HttpResponse<String> opened = mcp(mcpResource, access);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);

        // The token rides through to the tool's own route security (`auth: bearer`), so the
        // CALL must succeed, not just the transport gate — the Codex acceptance's second
        // finding: every AS-minted MCP token passed the door and failed every tool with
        // TQL-SEC-4143, because the member's derived audiences stopped at its address while
        // the token names the MCP resource below it.
        HttpResponse<String> called = call(mcpResource, access, opened);
        assertThat(called.statusCode()).as(called.body()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(called.body()).path("result");
        assertThat(result.path("isError").asBoolean(false)).as(called.body()).isFalse();
        assertThat(called.body()).as(called.body()).contains("data");
    }

    /**
     * A member named in Japanese: the document is at the wire-spelled well-known and names the
     * wire-spelled resource; the challenge carries that spelling, not the {@code ?} the header
     * folded a raw name to; and a grant for that resource opens the surface and runs the tool —
     * the gate, the mint and the member's audiences agree on the one spelling
     * (docs/audit-low-leads.md, unfiled 48).
     */
    @Test
    @org.junit.jupiter.api.Order(7)
    void aJapaneseMembersMcpSurfaceIsNamedAsTheWireSpellsIt() throws Exception {
        String wire = "http://localhost:" + port + "/%E5%8F%97%E6%B3%A8/_tesseraql/mcp";

        HttpResponse<String> metadata = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port
                        + "/.well-known/oauth-protected-resource/%E5%8F%97%E6%B3%A8/_tesseraql/mcp"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(metadata.body()).get("resource").asString()).isEqualTo(wire);

        HttpResponse<String> challenged = mcp(wire, null);
        assertThat(challenged.statusCode()).isEqualTo(401);
        String challenge = challenged.headers().firstValue("WWW-Authenticate").orElse("");
        assertThat(challenge)
                .contains("resource_metadata=\"http://localhost:" + port
                        + "/.well-known/oauth-protected-resource/%E5%8F%97%E6%B3%A8/_tesseraql/mcp\"")
                .doesNotContain("?");

        String[] session = signIn("alice");
        String form = "_csrf=" + enc(session[1]) + "&client_id=codex&redirect_uri="
                + enc("http://127.0.0.1:49681/callback/x") + "&state=jp"
                + "&code_challenge=" + enc(challenge(VERIFIER))
                + "&resource=" + enc(wire) + "&decision=approve";
        HttpResponse<String> approved = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/decision"))
                .header("Cookie", session[0])
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(303);
        String code = approved.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*[?&]code=([^&]+).*", "$1");
        HttpResponse<String> minted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&client_id=codex&code=" + enc(code)
                                + "&redirect_uri=" + enc("http://127.0.0.1:49681/callback/x")
                                + "&code_verifier=" + enc(VERIFIER)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(minted.statusCode()).as(minted.body()).isEqualTo(200);
        String access = MAPPER.readTree(minted.body()).get("access_token").asString();
        JsonNode payload = MAPPER.readTree(
                Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertThat(payload.get("aud").asString()).isEqualTo(wire);

        HttpResponse<String> opened = mcp(wire, access);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
        HttpResponse<String> called = call(wire, access, opened);
        assertThat(called.statusCode()).as(called.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(called.body()).path("result").path("isError")
                .asBoolean(false)).as(called.body()).isFalse();
        assertThat(called.body()).as(called.body()).contains("s2");
    }

    private static HttpResponse<String> mcp(String endpoint, String bearer) throws Exception {
        return mcp(endpoint, bearer, null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    }

    /** The items tool, called on the session {@code initialized} minted. */
    private static HttpResponse<String> call(String endpoint, String bearer,
            HttpResponse<String> initialized) throws Exception {
        return mcp(endpoint, bearer,
                initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"items.tool\",\"arguments\":{}}}");
    }

    private static HttpResponse<String> mcp(String endpoint, String bearer, String session,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aForgedTokenIsStillRefusedAtTheMember() throws Exception {
        HttpResponse<String> refused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/shop/api/secure"))
                .header("Authorization", "Bearer not.a.token")
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(401);
    }

    private static String acquireBearer(String loginId) throws Exception {
        HttpResponse<String> exchanged = exchange(loginId, null);
        assertThat(exchanged.statusCode()).as(exchanged.body()).isEqualTo(200);
        return MAPPER.readTree(exchanged.body()).path("token").asString();
    }

    private static HttpResponse<String> exchange(String loginId, String body) throws Exception {
        HttpResponse<String> login = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"" + loginId + "\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        String csrf = MAPPER.readTree(login.body()).path("csrfToken").asString();

        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/_tesseraql/token"))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf);
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.POST(HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : java.util.List.of("s1", "s2")) {
                statement.execute("create schema " + schema);
                statement.execute("create table " + schema
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + schema + ".items (name) values ('" + schema
                        + "')");
            }
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users "
                    + "(user_id, login_id, display_name, status, password_hash, password_algo,"
                    + " password_params) values ('u-alice','alice','alice','ACTIVE','" + hash
                    + "','pbkdf2','" + params + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-alice','staff','staff')");
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('u-alice','r-alice')");
            statement.execute("insert into tql_permissions"
                    + " (permission_id, permission_code, permission_name)"
                    + " values ('tql.app.use.*','tql.app.use.*','tql.app.use.*')");
            statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                    + " values ('r-alice','tql.app.use.*')");
        }
    }

    /** The user-admin example renamed to {@code name}, plus a bearer-protected items route. */
    private static Path appHome(String name, String schema) throws IOException {
        Path home = work.resolve("app-" + System.nanoTime());
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, home, path));
        }
        UserAdminAppCopy.prepare(home);
        Path exampleConfig = home.resolve("config/tesseraql.yml");
        String config = Files.readString(exampleConfig);
        // The member's egress allow list must play no part in validating stack tokens: the
        // derived key set is read from the shared framework database, never fetched through
        // the outbound gateway (the 2026-08-24 Codex acceptance found the fetch silently
        // host-denied on any member that had not allow-listed its own stack origin). The
        // example happens to allow-list localhost — strip it so this suite proves the
        // independence rather than riding the coincidence. Loud on drift.
        String allowLocalhost = "        - localhost\n";
        if (!config.contains(allowLocalhost)) {
            throw new IllegalStateException("The example's outbound allow list moved; update"
                    + " the strip so this suite keeps proving stack-token validation is"
                    + " independent of the member's egress policy");
        }
        Files.writeString(exampleConfig, config
                .replace(allowLocalhost, "")
                .replace("permission: user-admin.", "permission: " + name + ".")
                // The member sheds its own key source — under the stack issuer a declared
                // secret is a second issuer and refused (TQL-OAUTH-3001); its declared
                // audience stays and the origin joins it.
                .replace("secret: ${JWT_SECRET:dev-only-secret-change-me-in-production}",
                        ""));
        // No jwt block anywhere: the validation configuration is the stack's, derived. The
        // MCP transport gate is on (docs/audit-hardening.md decision 2), so the member's MCP
        // surface demands a token minted for ITS resource identifier.
        Files.writeString(home.resolve("config/overlay.yml"), """
                tesseraql:
                  app:
                    name: %s
                    version: 1.0.0
                  mcp:
                    auth: bearer
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                """.formatted(name, POSTGRES.getJdbcUrl(), schema, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path mcpDir = home.resolve("mcp");
        Files.createDirectories(mcpDir);
        Files.writeString(mcpDir.resolve("items.yml"), """
                version: tesseraql/v1
                id: items.tool
                kind: tool
                recipe: query-json
                description: Lists the items.
                security:
                  auth: bearer
                sources:
                  main:
                    sql:
                      file: items.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(mcpDir.resolve("items.sql"), "select id, name from items order by id\n");
        Path itemsDir = home.resolve("web/api/secure");
        Files.createDirectories(itemsDir);
        Files.writeString(itemsDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.secure
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(itemsDir.resolve("list.sql"),
                "select id, name from items order by id\n");
        return home;
    }

    private static Path packaged(Path home) throws IOException {
        Path pkg = work.resolve("pkg-" + System.nanoTime() + ".tqlapp");
        try (OutputStream stream = Files.newOutputStream(pkg);
                ZipOutputStream zip = new ZipOutputStream(stream);
                Stream<Path> files = Files.walk(home)) {
            files.filter(Files::isRegularFile).sorted().forEach(file -> {
                try {
                    zip.putNextEntry(
                            new ZipEntry(home.relativize(file).toString().replace('\\', '/')));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        return pkg;
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
