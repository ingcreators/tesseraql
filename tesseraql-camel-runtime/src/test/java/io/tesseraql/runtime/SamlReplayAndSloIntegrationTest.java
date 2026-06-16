package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.saml.LogoutRequest;
import io.tesseraql.saml.SamlRedirect;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the SAML hardening (design ch. 10.14, 20): single-use InResponseTo with
 * RelayState pinning, assertion replay rejection, deny-by-default for unsolicited responses,
 * signed HTTP-Redirect messages, and inbound (IdP-initiated) single logout.
 */
@Testcontainers
class SamlReplayAndSloIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String AUDIENCE = "https://sp.example.com/saml";
    private static final String RECIPIENT = "https://sp.example.com/_tesseraql/saml/acs";
    private static final String IDP_SSO = "https://idp.example.com/sso";
    private static final String IDP_SLO = "https://idp.example.com/slo";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static KeyPair idpKeys;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        idpKeys = SamlTestSupport.generateKeyPair();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void unsolicitedResponsesAreDeniedByDefault() throws Exception {
        assertThat(postAcs(response(null), null).statusCode()).isEqualTo(401);
    }

    @Test
    void inResponseToIsSingleUseAndRelayStatePinned() throws Exception {
        Map<String, String> login = login("/return-here");
        String requestId = authnRequestId(login.get("SAMLRequest"));
        // The SP-initiated redirect is signed (the SP signing key is configured).
        assertThat(login).containsKey("SigAlg").containsKey("Signature");

        // Wrong RelayState: rejected even though InResponseTo matches.
        assertThat(postAcs(response(requestId), "evil").statusCode()).isEqualTo(401);

        // The failed attempt consumed the pending request: a correct retry is also rejected.
        assertThat(postAcs(response(requestId), "/return-here").statusCode()).isEqualTo(401);

        // A fresh login with the matching RelayState succeeds exactly once.
        String secondId = authnRequestId(login("/return-here").get("SAMLRequest"));
        assertThat(postAcs(response(secondId), "/return-here").statusCode()).isEqualTo(200);
        assertThat(postAcs(response(secondId), "/return-here").statusCode()).isEqualTo(401);
    }

    @Test
    void assertionReplayIsRejectedAcrossRequests() throws Exception {
        String assertionId = "_a" + UUID.randomUUID();
        String firstId = authnRequestId(login(null).get("SAMLRequest"));
        assertThat(postAcs(response(firstId, assertionId), null).statusCode()).isEqualTo(200);

        // A different (valid) request carrying the same assertion id is a replay.
        String secondId = authnRequestId(login(null).get("SAMLRequest"));
        assertThat(postAcs(response(secondId, assertionId), null).statusCode()).isEqualTo(401);
    }

    @Test
    void signedIdpInitiatedLogoutInvalidatesTheSessionAndResponds() throws Exception {
        String requestId = authnRequestId(login(null).get("SAMLRequest"));
        HttpResponse<String> acs = postAcs(response(requestId), null);
        assertThat(acs.statusCode()).isEqualTo(200);
        String cookie = acs.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

        String logoutId = "_lr" + UUID.randomUUID();
        String logoutXml = new LogoutRequest("https://idp.example.com", RECIPIENT,
                "alice@idp.example.com", null).toXml(logoutId, Instant.now());
        String query = signedIdpQuery(SamlRedirect.deflateAndEncode(logoutXml));

        HttpResponse<String> slo = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/saml/slo?" + query))
                .header("Cookie", cookie)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(slo.statusCode()).isEqualTo(302);
        assertThat(slo.headers().firstValue("Set-Cookie").orElse("")).contains("Max-Age=0");
        String location = slo.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(IDP_SLO).contains("SAMLResponse=");
        // The LogoutResponse correlates to the inbound request and reports success.
        String responseXml = SamlRedirect.decodeAndInflate(
                queryParams(location.substring(location.indexOf('?') + 1)).get("SAMLResponse"));
        assertThat(responseXml).contains("InResponseTo=\"" + logoutId + "\"")
                .contains("status:Success");
    }

    @Test
    void unsignedLogoutRequestsAreRejected() throws Exception {
        String logoutXml = new LogoutRequest("https://idp.example.com", RECIPIENT,
                "alice@idp.example.com", null).toXml("_lr" + UUID.randomUUID(), Instant.now());
        String query = SamlRedirect.query("SAMLRequest",
                SamlRedirect.deflateAndEncode(logoutXml), null);
        HttpResponse<String> slo = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/saml/slo?" + query))
                .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(slo.statusCode()).isEqualTo(401);
    }

    // --- helpers ---

    /** GET /login and return the redirect query parameters. */
    private static Map<String, String> login(String relayState) throws Exception {
        String url = "http://localhost:" + runtime.port() + "/_tesseraql/saml/login";
        if (relayState != null) {
            url += "?RelayState=" + URLEncoder.encode(relayState, StandardCharsets.UTF_8);
        }
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(IDP_SSO);
        return queryParams(location.substring(location.indexOf('?') + 1));
    }

    private static Map<String, String> queryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String authnRequestId(String encodedRequest) {
        String xml = SamlRedirect.decodeAndInflate(encodedRequest);
        Matcher matcher = Pattern.compile("ID=\"([^\"]+)\"").matcher(xml);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String response(String inResponseTo) throws Exception {
        return response(inResponseTo, "_a" + UUID.randomUUID());
    }

    private static String response(String inResponseTo, String assertionId) throws Exception {
        return SamlTestSupport.signedResponse(idpKeys.getPrivate(), "alice@idp.example.com",
                AUDIENCE, RECIPIENT, Instant.now(),
                Map.of("uid", List.of("alice")), assertionId, inResponseTo);
    }

    private static String signedIdpQuery(String encodedLogoutRequest) throws Exception {
        return SamlRedirect.signedQuery("SAMLRequest", encodedLogoutRequest, null,
                idpKeys.getPrivate());
    }

    private static HttpResponse<String> postAcs(String samlResponseXml, String relayState)
            throws Exception {
        String body = "SAMLResponse=" + URLEncoder.encode(
                Base64.getEncoder()
                        .encodeToString(samlResponseXml.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        if (relayState != null) {
            body += "&RelayState=" + URLEncoder.encode(relayState, StandardCharsets.UTF_8);
        }
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + runtime.port() + "/_tesseraql/saml/acs"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws Exception {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-saml-replay-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Path saml = target.resolve("saml");
        Files.createDirectories(saml);
        Files.writeString(saml.resolve("idp.pem"),
                SamlTestSupport.publicKeyPem(idpKeys.getPublic()));
        // The SP signs its redirects with the fixed test key (PKCS#8 PEM).
        Files.writeString(saml.resolve("sp.pem"), SamlTestSupport.fixedPrivateKeyPem());

        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  saml:
                    enabled: true
                    sp:
                      audience: %s
                      acsUrl: %s
                      signingKey: saml/sp.pem
                    idp:
                      publicKey: saml/idp.pem
                      ssoUrl: %s
                      sloUrl: %s
                    attributes:
                      loginId: uid
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                AUDIENCE, RECIPIENT, IDP_SSO, IDP_SLO));
        return target;
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
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
