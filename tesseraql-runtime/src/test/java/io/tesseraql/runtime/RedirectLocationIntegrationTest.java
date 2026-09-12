package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.operations.attachment.JdbcAttachmentStore;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.auth.AuthStep;
import io.tesseraql.security.Principal;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A redirect lands where it says: every {@code Location} and {@code HX-Redirect} the framework
 * writes for a non-ASCII target reaches the wire percent-encoded, once, and a return target
 * that would leave this origin is refused. Read through {@code java.net.http} pinned to
 * HTTP/1.1 with {@code followRedirects(NEVER)} — the JDK decodes header bytes as ISO-8859-1, a
 * bijection, so the String IS the wire bytes — except where a tab matters (the JDK folds an
 * HTAB to a space), which read a raw socket. The only redirect ever followed is the Japanese
 * one, keyed on a distinct landing status: the JDK lands a raw Latin-1 target by accident, and
 * the root answers 200 to the mangled Japanese one, so neither "follow and land" nor "does not
 * 404" can be the assertion.
 *
 * <p>Three runtimes: one at the root (the literal, {@code _return}, expression, htmx, declared
 * header, paged-list {@code Link}, login round trip and attachment rows), one under a Japanese
 * base path with SCIM (the prefix rows and the SCIM writer), one hosted member at the root (the
 * activation redirect and the hosted login bounce) — kept apart so a fix that encodes the path
 * before the join, leaving the prefix raw, is red on its own rows. The embedding surface only:
 * a Japanese-named member behind the gateway is red for the routing reason (TQL-APP-4040)
 * until the router slice lands.
 */
@Testcontainers
class RedirectLocationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final String JP = "%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7"; // the encoded form of "受注一覧"
    static final String JU = "%E5%8F%97%E6%B3%A8"; // the encoded form of "受注"
    static final String TENPU = "%E6%B7%BB%E4%BB%98"; // the encoded form of "添付"
    static final String MEISAI = "%E6%98%8E%E7%B4%B0"; // the encoded form of "明細"
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static TesseraqlRuntime root;
    static TesseraqlRuntime prefixed;
    static TesseraqlRuntime member;
    static Path rootHome;
    static Path prefixedHome;
    static Path memberHome;

    @BeforeAll
    static void start() throws Exception {
        seed();
        rootHome = RedirectLocationApp.prepare(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), "", sha256Hex("key-a"), false);
        root = TesseraqlRuntime.start(rootHome, 0);
        prefixedHome = RedirectLocationApp.prepare(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), "/受注", sha256Hex("key-a"), true);
        prefixed = TesseraqlRuntime.start(prefixedHome, 0);
        memberHome = RedirectLocationApp.prepare(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), "", sha256Hex("key-a"), false);
        member = TesseraqlRuntime.start(memberHome, 0);
        // The real AuthStep('activate') behind a principal holding one grant for this member,
        // on a Japanese page path; then the runtime becomes a hosted member.
        HttpMounts.of(member.context()).mount("GET", "/受注/act", "act");
        Pipelines.of(member.context()).compiling(List.of()).pipeline("act")
                .process(exchange -> exchange.setProperty(TesseraqlProperties.PRINCIPAL,
                        new Principal("u", "u", "U", null, List.of(), List.of("buyer"), List.of(),
                                Map.of(), List.of(new Principal.RoleGrant("buyer", "member",
                                        List.of())),
                                List.of())))
                .process(new AuthStep("activate"))
                .process(exchange -> {
                    exchange.response().header(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
                    exchange.setBody("activated");
                });
        member.context().lookup(RouteEdge.BEAN, RouteEdge.class).refreshAll();
        member.context().bind(TesseraqlProperties.STACK_MEMBER_BEAN, "member");
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : List.of(root, prefixed, member)) {
            if (runtime != null) {
                runtime.close();
            }
        }
        delete(rootHome);
        delete(prefixedHome);
        delete(memberHome);
    }

    // ---- a literal location:

    @Test
    void aLiteralJapaneseLocationIsPercentEncodedOnTheWire() throws Exception {
        HttpResponse<Void> response = get(root, "/go/jp");

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(location(response)).isEqualTo("/" + JP);
    }

    /** Two bytes, never %E9 and never the raw 0xE9 (twelve of fourteen followers 404 it). */
    @Test
    void aLatin1LocationIsPercentEncodedAsUtf8OnTheWire() throws Exception {
        HttpResponse<Void> response = get(root, "/go/latin");

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(location(response)).isEqualTo("/caf%C3%A9");
        assertThat(location(response).chars().allMatch(c -> c < 0x80)).isTrue();
    }

    @Test
    void aPreEncodedLocationIsNotEncodedTwiceOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/pre"))).isEqualTo("/caf%C3%A9");
    }

    /** Beside a Japanese segment, so a double-encoder with an ASCII fast path is caught too. */
    @Test
    void aPreEncodedTripletBesideAJapaneseSegmentIsNotEncodedTwiceOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/premix"))).isEqualTo("/" + JU + "/caf%C3%A9");
    }

    /** Today the raw `?` per char swallows the real query: /?????x=1. */
    @Test
    void theQueryOfAJapaneseLocationSurvivesOnTheWire() throws Exception {
        String location = location(get(root, "/go/q"));

        assertThat(location).isEqualTo("/" + JP + "?x=1");
        assertThat(location.indexOf('?')).isEqualTo(location.lastIndexOf('?'));
    }

    /** RFC 3986's set, not printable ASCII: the JDK follower throws on a raw brace. */
    @Test
    void anAsciiGraphicOutsideTheUriSetIsEncodedOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/brace"))).isEqualTo("/a%7Cb%5Ec");
    }

    @Test
    void anAbsoluteRedirectWithANonAsciiPathIsEncodedOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/absjp"))).isEqualTo("https://example.test/" + JU);
    }

    /** The one follow: the Japanese target lands on ITS route, told apart by status 228 (root answers 230). */
    @Test
    void theJapaneseRedirectLandsOnItsRoute() throws Exception {
        HttpClient follower = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Void> landed = follower.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + root.port() + "/go/jp")).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(landed.statusCode()).isEqualTo(228);
    }

    // ---- location: back

    @Test
    void backWithAJapaneseReturnFieldIsPercentEncodedOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/back?_return=%2F" + JP + "%3Fpage%3D2")))
                .isEqualTo("/" + JP + "?page=2");
    }

    @Test
    void backWithAnEncodedReturnFieldIsNotEncodedTwiceOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/back?_return=%2Fcaf%25C3%25A9")))
                .isEqualTo("/caf%C3%A9");
    }

    @Test
    void backWithAMixedReturnFieldIsEncodedOnceOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/back?_return=%2F" + JU + "%2Fcaf%25C3%25A9")))
                .isEqualTo("/" + JU + "/caf%C3%A9");
    }

    /** A form-encoded space in the query is a wire `+`; the seam leaves it a `+`. */
    @Test
    void backKeepsAFormEncodedSpaceInTheReturnQuery() throws Exception {
        assertThat(location(get(root, "/go/back?_return=%2F" + JP + "%3Fq%3Da%2Bb")))
                .isEqualTo("/" + JP + "?q=a+b");
    }

    /**
     * A browser deletes a tab from a URL before parsing, so a raw {@code /<TAB>/host} navigates
     * off-site; the gate refuses it and the fallback is the root. Raw socket: the JDK folds the
     * tab to a space and would hide the byte.
     */
    @Test
    void aReturnWithATabIsRefusedByTheGate() throws Exception {
        Raw back = raw(root, "GET", "/go/back?_return=%2F%09%2F127.0.0.1%3A1%2Fpwned", null, null);
        assertThat(back.status).isEqualTo(303);
        assertThat(back.location).isEqualTo("/");

        Raw backslash = raw(root, "GET", "/go/back?_return=%2F%09%5Cevil.example%2Fx", null, null);
        assertThat(backslash.location).isEqualTo("/");
    }

    // ---- htmx

    @Test
    void anHtmxCallerGetsTheEncodedTargetInHxRedirect() throws Exception {
        HttpResponse<Void> response = get(root, "/go/jp", "HX-Request", "true");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("HX-Redirect")).contains("/" + JP);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    // ---- the {expression} arm

    @Test
    void anExpressionValueWithASpaceIsPercent20OnTheWire() throws Exception {
        assertThat(location(get(root, "/go/expr?name=a%20b&id=1"))).isEqualTo("/orders/a%20b/1");
    }

    @Test
    void anExpressionValueInJapaneseIsEncodedOnceOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/expr?name=" + JU + "&id=42")))
                .isEqualTo("/orders/" + JU + "/42");
    }

    @Test
    void anExpressionValueWithASlashStaysASegmentOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/expr?name=a%2Fb&id=1"))).isEqualTo("/orders/a%2Fb/1");
    }

    // ---- the declared headers: block (docs/response-shaping.md's Location on a 201)

    @Test
    void aDeclaredLocationWithAJapaneseKeyAnswers201WithAnEncodedLocation() throws Exception {
        HttpResponse<Void> response = get(root, "/go/hdr?id=" + JU + "-001");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(location(response)).isEqualTo("/api/items/" + JU + "-001");
        // Only the URI-reference headers are encoded; an ordinary declared header is not.
        assertThat(response.headers().firstValue("X-Msg").orElse("")).doesNotContain("%");
        assertThat(location(get(root, "/go/hdr?id=42"))).isEqualTo("/api/items/42");
    }

    @Test
    void aPlaceholderInADeclaredLocationIsAPathSegmentOnTheWire() throws Exception {
        assertThat(location(get(root, "/go/hdr?id=a%2Fb"))).isEqualTo("/api/items/a%2Fb");
    }

    @Test
    void aDeclaredHxRedirectIsEncodedOnTheWire() throws Exception {
        HttpResponse<Void> response = get(root, "/go/hxhdr?id=" + JU);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("HX-Redirect")).contains("/x/" + JU);
    }

    @Test
    void aDeclaredLocationBuiltFromACallerValueNeverShipsATab() throws Exception {
        Raw authored = raw(root, "GET", "/authored?next=%2F%09%2F127.0.0.1%3A1%2Fpwned", null,
                null);

        assertThat(authored.status).isEqualTo(302);
        assertThat(authored.location.chars().allMatch(c -> c > 0x20 && c < 0x7F)).isTrue();
        assertThat(authored.location).doesNotStartWith("//");
    }

    // ---- the paged list's Link header (the same seam family, ridden by decision)

    /**
     * RFC 8288 {@code Link} names the next and previous pages from the request URI, which the
     * runtime hands over decoded: under a Japanese route path the target was one byte per
     * character — {@code </??/???page=2>}, the root with a garbage query for any client that
     * walks it. Both targets are percent-encoded once, so the only {@code ?} is the delimiter.
     */
    @Test
    void theNextLinkOfAJapaneseRouteIsPercentEncoded() throws Exception {
        HttpResponse<Void> first = get(root, "/" + JU + "/" + MEISAI);

        assertThat(first.statusCode()).isEqualTo(200);
        String next = first.headers().firstValue("Link").orElse("(absent)");
        assertThat(next).isEqualTo("</" + JU + "/" + MEISAI + "?page=2>; rel=\"next\"");
        String target = next.substring(1, next.indexOf('>'));
        assertThat(target.chars().allMatch(c -> c < 0x80)).isTrue();
        assertThat(target.indexOf('?')).isEqualTo(target.lastIndexOf('?'));

        String prev = get(root, "/" + JU + "/" + MEISAI + "?page=2").headers()
                .firstValue("Link").orElse("(absent)");
        assertThat(prev).isEqualTo("</" + JU + "/" + MEISAI + "?page=1>; rel=\"prev\"");
    }

    // ---- the login round trip (ride-along: the query exactly once)

    @Test
    void theLoginBounceCarriesTheQueryExactlyOnce() throws Exception {
        HttpResponse<Void> bounce = get(root, "/" + JU + "/secret?q=1&r=2", "Accept", "text/html");

        assertThat(bounce.statusCode()).isEqualTo(302);
        assertThat(location(bounce))
                .isEqualTo("/_tesseraql/login?redirect=%2F" + JU + "%2Fsecret%3Fq%3D1%26r%3D2");
    }

    @Test
    void theLoginBounceOffAnAsciiPageCarriesTheQueryExactlyOnce() throws Exception {
        assertThat(location(get(root, "/plain/secret?q=1&r=2", "Accept", "text/html")))
                .isEqualTo("/_tesseraql/login?redirect=%2Fplain%2Fsecret%3Fq%3D1%26r%3D2");
    }

    @Test
    void signingInReturnsToTheJapanesePageWithItsQuery() throws Exception {
        HttpResponse<Void> signedIn = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + root.port() + "/_tesseraql/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html")
                .POST(HttpRequest.BodyPublishers.ofString("loginId=admin&password=s3cret&redirect="
                        + URLEncoder.encode("/受注/secret?q=1&r=2", StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.discarding());

        assertThat(signedIn.statusCode()).isEqualTo(303);
        assertThat(location(signedIn)).isEqualTo("/" + JU + "/secret?q=1&r=2");

        String setCookie = signedIn.headers().firstValue("Set-Cookie").orElse("");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        HttpResponse<Void> landed = get(root, location(signedIn), "Cookie", cookie,
                "Accept", "text/html");
        assertThat(landed.statusCode()).isEqualTo(226);
        assertThat(landed.headers().firstValue("X-Q")).contains("1");
        assertThat(landed.headers().firstValue("X-R")).contains("2");
    }

    @Test
    void signingInWithATabInTheRedirectLandsOnTheRoot() throws Exception {
        Raw signedIn = raw(root, "POST", "/_tesseraql/login",
                "Content-Type: application/x-www-form-urlencoded\r\nAccept: text/html\r\n",
                "loginId=admin&password=s3cret&redirect=%2F%09%2F127.0.0.1%3A1%2Fpwned"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(signedIn.status).isEqualTo(303);
        assertThat(signedIn.location).isEqualTo("/");
    }

    // ---- the writers outside the funnel

    @Test
    void anUploadUnderAJapanesePathIdentifiesItWithAnEncodedLocation() throws Exception {
        String boundary = "redirect-location-boundary";
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"note.txt\"\r\nContent-Type: text/plain\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes("hello".getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> uploaded = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + root.port() + "/" + TENPU + "/R-1/files"))
                .header("X-API-Key", "key-a")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(uploaded.statusCode()).isEqualTo(201);
        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(uploaded.body()).get("id").asText();
        assertThat(uploaded.headers().firstValue("Location"))
                .contains("/" + TENPU + "/R-1/files/" + id);
    }

    @Test
    void anActivationRedirectToAJapanesePageIsPercentEncoded() throws Exception {
        HttpResponse<Void> activated = get(member, "/" + JU + "/act?q=1", "Accept", "text/html");

        assertThat(activated.statusCode()).isEqualTo(302);
        assertThat(location(activated)).isEqualTo("/_as/buyer/" + JU + "/act?q=1");
    }

    @Test
    void theLoginBounceOfAHostedMemberCarriesTheQueryExactlyOnce() throws Exception {
        assertThat(location(get(member, "/plain/secret?q=1&r=2", "Accept", "text/html")))
                .isEqualTo("/_tesseraql/login?redirect=%2Fplain%2Fsecret%3Fq%3D1%26r%3D2");
    }

    @Test
    void aScimCreateUnderAJapaneseBasePathAnswers201WithAnEncodedLocation() throws Exception {
        String body = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"asmith-" + System.nanoTime() + "\"}";
        Raw created = raw(prefixed, "POST", "/" + JU + "/scim/v2/Users",
                "Authorization: Bearer " + scimToken()
                        + "\r\nContent-Type: application/scim+json\r\n",
                body.getBytes(StandardCharsets.UTF_8));

        assertThat(created.status).isEqualTo(201);
        assertThat(created.location).startsWith("/" + JU + "/scim/v2/Users/");
        assertThat(created.location.chars().allMatch(c -> c > 0x20 && c < 0x7F)).isTrue();
    }

    // ---- the Japanese base path (its own runtime)

    @Test
    void aJapaneseBasePathIsPercentEncodedOnTheWire() throws Exception {
        assertThat(location(get(prefixed, "/" + JU + "/go/ascii"))).isEqualTo("/" + JU + "/landed");
    }

    @Test
    void aJapaneseBasePathAndAJapaneseLiteralAreBothEncoded() throws Exception {
        assertThat(location(get(prefixed, "/" + JU + "/go/jp"))).isEqualTo("/" + JU + "/" + JP);
    }

    @Test
    void theLoginBounceUnderAJapaneseBasePathIsEncodedAndCarriesTheQueryOnce() throws Exception {
        assertThat(location(get(prefixed, "/" + JU + "/plain/secret?q=1", "Accept", "text/html")))
                .isEqualTo("/" + JU + "/_tesseraql/login?redirect=%2Fplain%2Fsecret%3Fq%3D1");
    }

    @Test
    void anHtmxCallerUnderAJapaneseBasePathGetsTheEncodedPrefix() throws Exception {
        HttpResponse<Void> response = get(prefixed, "/" + JU + "/go/ascii", "HX-Request", "true");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("HX-Redirect")).contains("/" + JU + "/landed");
    }

    // ---- plumbing

    private static HttpResponse<Void> get(TesseraqlRuntime runtime, String path, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .timeout(Duration.ofSeconds(10));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElse("(absent)");
    }

    record Raw(int status, String location) {
    }

    /** One raw HTTP/1.1 exchange: the Location bytes exactly as sent. */
    private static Raw raw(TesseraqlRuntime runtime, String method, String target,
            String extraHeaders, byte[] body) throws Exception {
        try (Socket socket = new Socket("localhost", runtime.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: localhost\r\nConnection: close\r\n");
            if (extraHeaders != null) {
                request.append(extraHeaders);
            }
            if (body != null) {
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            InputStream in = socket.getInputStream();
            String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            String head = text.contains("\r\n\r\n")
                    ? text.substring(0, text.indexOf("\r\n\r\n"))
                    : text;
            String location = null;
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Location")) {
                    location = line.substring(colon + 1).trim();
                }
            }
            return new Raw(Integer.parseInt(head.substring(9, 12)), location);
        }
    }

    private static String scimToken() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = "{\"exp\":" + (System.currentTimeMillis() / 1000L + 3600)
                + ",\"aud\":[\"https://app.example.com\"],\"sub\":\"idp\",\"roles\":[\"SCIM\"]}";
        String payload = encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("dev-only-secret-change-me-in-production"
                .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seed() throws Exception {
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, status,"
                    + " password_hash, password_algo, password_params) values ('u1','admin',"
                    + "'Administrator','ACTIVE','" + encoder.encode("s3cret") + "','pbkdf2','"
                    + encoder.defaultParams() + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r1','USER','User')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
            statement.execute("create table scim_users (id serial primary key,"
                    + " user_name varchar(200) not null unique, given_name varchar(200),"
                    + " family_name varchar(200), email varchar(320), active boolean default true,"
                    + " external_id varchar(200))");
            // Two rows behind the one-row-a-page list, so page 1 has a next and page 2 a prev.
            statement.execute("create table order_lines (id serial primary key,"
                    + " item varchar(50) not null)");
            statement.execute("insert into order_lines (item) values ('bolt'), ('nut')");
        }
        PGSimpleDataSource main = new PGSimpleDataSource();
        main.setUrl(POSTGRES.getJdbcUrl());
        main.setUser(POSTGRES.getUsername());
        main.setPassword(POSTGRES.getPassword());
        new JdbcAttachmentStore(main).ensureSchema();
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void delete(Path target) throws IOException {
        if (target == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(target)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
