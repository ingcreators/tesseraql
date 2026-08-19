package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.oauth.SigningKeys;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        new AppInstaller().install(packaged(appHome()), installRoot);
        // The origin must be declared before the gateway binds, so the port is picked first.
        port = freePort();
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
                        """.formatted(port, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword()));
        gateway = MultiAppGateway.start(installRoot, port);
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
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(header.get("kid").asText()).isEqualTo(SigningKeys.INITIAL_KID);

        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("iss").asText()).isEqualTo("http://localhost:" + port);
        assertThat(payload.get("sub").asText()).isEqualTo("u-alice");
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
        assertThat(MAPPER.readTree(accepted.body()).get("data").get(0).get("name").asText())
                .isEqualTo("s1");
    }

    @Test
    void aMemberScopedTokenCarriesTheMembersAddressAndWorksThere() throws Exception {
        HttpResponse<String> exchanged = exchange("alice", "{\"appName\":\"shop\"}");
        assertThat(exchanged.statusCode()).as(exchanged.body()).isEqualTo(200);
        String token = MAPPER.readTree(exchanged.body()).path("token").asText();

        JsonNode payload = MAPPER.readTree(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload.get("aud").asText())
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
                + "&code_challenge=" + enc("a-challenge-of-plausible-length-0123456789abcdef")
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

    private static String authorizeQuery() {
        return "/_tesseraql/oauth/authorize?client_id=codex&response_type=code"
                + "&redirect_uri=" + enc("http://127.0.0.1:49681/callback/x")
                + "&state=xyz&code_challenge_method=S256"
                + "&code_challenge=" + enc("a-challenge-of-plausible-length-0123456789abcdef")
                + "&resource=" + enc("http://localhost:" + port + "/shop");
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
                MAPPER.readTree(login.body()).path("csrfToken").asText()};
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
        return MAPPER.readTree(exchanged.body()).path("token").asText();
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
        String csrf = MAPPER.readTree(login.body()).path("csrfToken").asText();

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
            statement.execute("create schema s1");
            statement.execute(
                    "create table s1.items (id serial primary key, name varchar(200) not null)");
            statement.execute("insert into s1.items (name) values ('s1')");
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

    /** The user-admin example renamed to {@code shop}, plus a bearer-protected items route. */
    private static Path appHome() throws IOException {
        Path home = work.resolve("app-" + System.nanoTime());
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, home, path));
        }
        Path exampleConfig = home.resolve("config/tesseraql.yml");
        Files.writeString(exampleConfig, Files.readString(exampleConfig)
                .replace("permission: user-admin.", "permission: shop.")
                // The member sheds its own key source — under the stack issuer a declared
                // secret is a second issuer and refused (TQL-OAUTH-3001); its declared
                // audience stays and the origin joins it.
                .replace("secret: ${JWT_SECRET:dev-only-secret-change-me-in-production}",
                        ""));
        // No jwt block anywhere: the validation configuration is the stack's, derived.
        Files.writeString(home.resolve("config/overlay.yml"), """
                tesseraql:
                  app:
                    name: shop
                    version: 1.0.0
                db:
                  main:
                    url: %s&currentSchema=s1
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
