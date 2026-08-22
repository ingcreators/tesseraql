package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for single-port multi-app routing (design ch. 32.7). Two installed apps are
 * fronted by one gateway port and routed by the derived {@code /<name>/} prefix; each reaches only
 * its own isolated app, and an unknown app returns 404.
 */
@Testcontainers
class MultiAppGatewayIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static MultiAppGateway gateway;
    static Path installRoot;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-gateway-it");
        installApp("shop-a", "a", List.of());
        installApp("shop-b", "b", List.of("tenant-b"));
        // The two applications isolate their business data by schema, so their main coordinates
        // differ — the stack supplies the framework connection, exactly the arrangement
        // TQL-APP-4211 would otherwise refuse (docs/stack-architecture.md decision 22).
        Files.writeString(installRoot.resolve(
                io.tesseraql.operations.app.StackSettings.FILE_NAME),
                """
                        framework:
                          datasource:
                            jdbcUrl: %s
                            username: %s
                            password: %s
                        """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword()));
        gateway = MultiAppGateway.start(installRoot, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        if (installRoot != null) {
            deleteRecursively(installRoot);
        }
    }

    @Test
    void routesByAppPrefixOnOnePort() throws Exception {
        assertThat(gateway.appNames()).containsExactlyInAnyOrder("shop-a", "shop-b");

        assertThat(itemName("shop-a")).isEqualTo("from-a");
        assertThat(itemName("shop-b")).isEqualTo("from-b");
    }

    /**
     * Closing the gateway closes what it opened.
     *
     * <p>It closed the hosted app and stopped the HTTP server, and left the client and the
     * executor behind, so a host that restarts a gateway accumulated both. The relay is
     * vertx-http-proxy now, so what has to be released is the Vert.x instance carrying the event
     * loops together with the server and the outbound client — the same defect one layer down.
     */
    @Test
    void closingTheGatewayReleasesWhatItOpened() throws Exception {
        MultiAppGateway second = MultiAppGateway.start(installRoot, 0);
        io.vertx.core.Vertx vertx = field(second, "vertx", io.vertx.core.Vertx.class);
        int port = second.port();
        assertThat(statusOf(second, "/shop-a/api/items")).isEqualTo(200);

        second.close();

        assertThat(vertx.deploymentIDs()).as("the Vert.x instance is closed").isEmpty();
        assertThatThrownBy(() -> java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + port + "/shop-a/api/items")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()))
                .as("and the port it fronted on is released")
                .isInstanceOf(java.io.IOException.class);
    }

    private static <T> T field(MultiAppGateway gateway, String name, Class<T> type)
            throws Exception {
        java.lang.reflect.Field field = MultiAppGateway.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(gateway));
    }

    /**
     * {@code host --app-name} narrows to one member of the stack without changing its address —
     * a filter, never a second deployment shape (docs/stack-architecture.md decision 12): the
     * member emits the same URLs narrowed as it does beside its neighbours.
     */
    @Test
    void narrowingHostsOneMemberAtItsUnchangedAddress() throws Exception {
        try (MultiAppGateway narrowed = MultiAppGateway.start(installRoot, 0,
                new MultiAppGateway.Settings(), "shop-a")) {
            assertThat(narrowed.appNames()).containsExactly("shop-a");
            assertThat(statusOf(narrowed, "/shop-a/api/items")).isEqualTo(200);
            assertThat(statusOf(narrowed, "/shop-b/api/items"))
                    .as("the neighbour is not hosted, and the member's address did not move")
                    .isEqualTo(404);
        }
    }

    /** A name the stack does not hold is refused with the members that would have worked. */
    @Test
    void narrowingToAnUnknownNameListsTheMembers() {
        assertThatThrownBy(() -> MultiAppGateway.start(installRoot, 0,
                new MultiAppGateway.Settings(), "shop-x"))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("shop-x")
                .hasMessageContaining("shop-a")
                .hasMessageContaining("shop-b");
    }

    @Test
    void unknownAppReturns404() throws Exception {
        HttpResponse<String> response = get("/nope/api/items");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("TQL-APP-4040");
    }

    @Test
    void enforcesTenantEntitlementAtTheFrontDoor() throws Exception {
        // shop-b is entitled to tenant-b only; a request declaring another tenant is refused
        // before it reaches the app, while the entitled tenant passes through.
        HttpResponse<String> denied = getWithTenant("/shop-b/api/items", "tenant-x");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("TQL-APP-4030");

        HttpResponse<String> allowed = getWithTenant("/shop-b/api/items", "tenant-b");
        assertThat(allowed.statusCode()).isEqualTo(200);

        // shop-a has no entitlement list, so every tenant is served.
        assertThat(getWithTenant("/shop-a/api/items", "tenant-x").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> getWithTenant(String path, String tenantId)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + path))
                .header("X-Tenant-Id", tenantId)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * There is one address, and a hostname is not it (docs/stack-architecture.md Decision 12).
     *
     * <p>Host-header routing went with independent hosting. Kept as a test because "the gateway
     * ignores the Host header" is the property that replaced a mode, not an absence of one.
     */
    @Test
    void aHostnameIsNotAnAddress() throws Exception {
        assertThat(itemName("shop-a")).isEqualTo("from-a");

        String response = rawGet(gateway, "/api/items", "shop-a.localhost");
        assertThat(response)
                .as("a hostname is not an address in stack mode")
                .startsWith("HTTP/1.1 404");
    }

    /**
     * A page rendered through the framework shell emits its URLs under the prefix, and they
     * answer.
     *
     * <p>This is what the base-path design exists for (docs/base-path.md). Before it, a page
     * under a prefix returned 200 with its stylesheet at {@code /assets/…} and
     * nothing at that address — a page that loaded and could not be used. The multi-app tests
     * all exercised a JSON route, which emits no links and so survived a prefix by accident.
     *
     * <p>Scoped to the framework templates; the bundled apps carry their own absolute URLs until
     * their slice lands.
     */
    @Test
    void aShellPageUnderThePrefixEmitsUrlsThatAnswer() throws Exception {
        java.net.http.HttpResponse<String> page = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + gateway.port() + "/shop-a/users")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);

        assertThat(page.body())
                .as("the shell's asset URLs carry the prefix")
                .contains("/shop-a/assets/");
        assertThat(page.body())
                .as("and none is left rooted at the origin, where nothing answers")
                .doesNotContain("href=\"/assets/")
                .doesNotContain("src=\"/assets/");

        // And every one of them answers. Asserting on the markup alone was what let the original
        // defect through in the other direction: a page can name a perfectly-formed address that
        // nothing serves, and only a request finds out.
        List<String> assetUrls = assetUrlsIn(page.body());
        assertThat(assetUrls).isNotEmpty();
        for (String url : assetUrls) {
            assertThat(statusOf(gateway, url)).as(url).isEqualTo(200);
        }

        assertThat(statusOf(gateway, "/assets/_tesseraql/tesseraql.css"))
                .as("the origin's asset tree is the stack surface runtime's, not this app's"
                        + " (docs/root-portal.md)")
                .isEqualTo(200);
    }

    /**
     * The stack's sign-in page, end to end: the login page is markup the framework ships, not
     * the application's, and it is the one page an operator meets before anything else. It is
     * the <em>stack's</em> now — served once at the origin scope; a hosted member mounts no
     * copy (docs/stack-shells.md structural decision 3) — so the page must post back to itself
     * at the origin, and every asset it names must answer there.
     */
    @Test
    void theStacksSignInPagePostsBackToItselfAtTheOrigin() throws Exception {
        java.net.http.HttpResponse<String> page = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"
                        + gateway.port() + "/_tesseraql/login")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("action=\"/_tesseraql/login\"");
        assertThat(page.body())
                .as("nothing on the origin's page is rooted under a member's prefix")
                .doesNotContain("=\"/shop-a/")
                .doesNotContain("=\"/shop-b/");

        for (String url : originAssetUrlsIn(page.body())) {
            assertThat(statusOf(gateway, url)).as(url).isEqualTo(200);
        }

        assertThat(statusOf(gateway, "/shop-a/_tesseraql/login"))
                .as("the member's own login page copy is gone; only the POST transport remains")
                .isIn(404, 405);
    }

    /** Every {@code href}/{@code src} in the page pointing into the asset tree, de-duplicated. */
    private static List<String> assetUrlsIn(String html) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:href|src)=\"(/shop-a/assets/[^\"#]+)").matcher(html);
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return List.copyOf(urls);
    }

    /** The origin-scope form of {@link #assetUrlsIn}: the stack surface runtime's asset tree. */
    private static List<String> originAssetUrlsIn(String html) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:href|src)=\"(/assets/[^\"#]+)").matcher(html);
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return List.copyOf(urls);
    }

    /**
     * The framework's own endpoints move under the prefix with the application's.
     *
     * <p>They are mounted by hand in the runtime's own routes — forty-seven
     * {@code rest().get("/_tesseraql/…")} calls across five classes — not through the
     * compiler's single mount point. Setting the prefix on Camel's context-wide REST
     * configuration reaches all of them at once (docs/base-path.md); concatenating it per route
     * would have had to find every one, and could have missed one silently.
     */
    @Test
    void frameworkEndpointsMoveUnderThePrefixToo() throws Exception {
        assertThat(statusOf(gateway, "/shop-a/_tesseraql/health"))
                .as("a hand-mounted framework endpoint answers under the app's prefix")
                .isEqualTo(200);

        // The origin used to be a 404 here; since docs/root-portal.md it is the stack surface
        // runtime's scope, so the same endpoint answers there as the stack's own — a different
        // runtime, not this application's leaked out.
        assertThat(statusOf(gateway, "/_tesseraql/health"))
                .as("the origin scope answers from the stack surface runtime")
                .isEqualTo(200);
    }

    /**
     * The root does exactly one thing — 307 — and configuration chooses only the target
     * (docs/stack-architecture.md Decision 24): the portal when the stack file names nothing,
     * {@code /<name>} when it names an application. The second case restarts a gateway over the
     * same install root with the pointer added, then restores the file.
     */
    @Test
    void theRootAlwaysRedirects() throws Exception {
        java.net.http.HttpResponse<String> root = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + gateway.port() + "/")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(root.statusCode()).isEqualTo(307);
        assertThat(root.headers().firstValue("Location")).contains("/_tesseraql/portal");

        Path stackFile = installRoot.resolve(
                io.tesseraql.operations.app.StackSettings.FILE_NAME);
        String original = Files.readString(stackFile);
        Files.writeString(stackFile, original + "root:\n  redirect: shop-a\n");
        try (MultiAppGateway pointed = MultiAppGateway.start(installRoot, 0)) {
            java.net.http.HttpResponse<String> redirected = java.net.http.HttpClient
                    .newHttpClient().send(
                            java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    "http://localhost:" + pointed.port() + "/")).build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(redirected.statusCode()).isEqualTo(307);
            assertThat(redirected.headers().firstValue("Location")).contains("/shop-a");
        } finally {
            Files.writeString(stackFile, original);
        }
    }

    /**
     * The origin scope is the stack surface runtime's (docs/root-portal.md): the stack's own
     * sign-in page answers at {@code /_tesseraql/login}, posts back to itself at the origin, and
     * its assets answer at the origin's {@code /assets/*} — the same page every member serves
     * under its prefix, at the scope the portal will send anonymous users to.
     */
    @Test
    void theOriginScopeIsTheSurfaceRuntimes() throws Exception {
        java.net.http.HttpResponse<String> page = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"
                        + gateway.port() + "/_tesseraql/login")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("action=\"/_tesseraql/login\"");

        java.util.regex.Matcher assets = java.util.regex.Pattern
                .compile("(?:href|src)=\"(/assets/[^\"#]+)").matcher(page.body());
        assertThat(assets.find()).as("the page references origin-scope assets").isTrue();
        do {
            assertThat(statusOf(gateway, assets.group(1))).as(assets.group(1)).isEqualTo(200);
        } while (assets.find());

        // The development surface deliberately does not mount at the origin: an origin-scope
        // Studio would edit the portal application's own extracted tree (its stack shell is
        // slice 8's own design). The ops shell IS an origin-scope surface now
        // (docs/stack-shells.md structural decision 2): unauthenticated, it demands the
        // origin's own sign-in rather than reading as unserved.
        assertThat(statusOf(gateway, "/_tesseraql/studio/ui"))
                .as("Studio is not an origin-scope surface").isEqualTo(404);
        assertThat(statusOf(gateway, "/_tesseraql/ops/console"))
                .as("the ops shell answers at the origin").isEqualTo(401);
        // And a hosted member serves no console of its own anymore — one console per stack.
        assertThat(statusOf(gateway, "/shop-a/_tesseraql/ops/console"))
                .as("a hosted member carries no per-member console").isEqualTo(404);
    }

    private static int statusOf(MultiAppGateway target, String path) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + target.port() + path)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** Sends a raw HTTP/1.1 GET so a custom Host header can be set (the HTTP client forbids it). */
    private static String rawGet(MultiAppGateway target, String path, String hostName)
            throws IOException {
        try (java.net.Socket socket = new java.net.Socket("localhost", target.port())) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostName
                    + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream()
                    .write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return response;
        }
    }

    private static String itemName(String appId) throws Exception {
        HttpResponse<String> response = get("/" + appId + "/api/items");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(response.body()).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("name").asText();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[]{"a", "b"}) {
                statement.execute("create schema " + schema);
                statement.execute("create table " + schema
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute(
                        "insert into " + schema + ".items (name) values ('from-" + schema + "')");
            }
        }
    }

    private static void installApp(String appId, String schema, List<String> entitledTenants)
            throws IOException {
        Path appHome = installRoot.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                tesseraql:
                  security:
                    mtls:
                      forwardedHeader: X-Client-Cert
                """.formatted(POSTGRES.getJdbcUrl(), schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path itemsDir = appHome.resolve("web/api/items");
        Files.createDirectories(itemsDir);
        Files.writeString(itemsDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
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
        Files.writeString(itemsDir.resolve("list.sql"), "select id, name from items order by id\n");

        new AppCatalog(installRoot).register(new InstalledApp(
                appId, "1.0.0", appId + "/1.0.0", entitledTenants));
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
}
