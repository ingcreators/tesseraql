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
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for single-port multi-app routing (design ch. 32.7). Two installed apps are
 * fronted by one gateway port and routed by the {@code /apps/<appId>/} prefix; each reaches only
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
        gateway = MultiAppGateway.start(installRoot, 0, MultiAppGateway.Mode.SUITE);
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
        assertThat(gateway.appIds()).containsExactlyInAnyOrder("shop-a", "shop-b");

        assertThat(itemName("shop-a")).isEqualTo("from-a");
        assertThat(itemName("shop-b")).isEqualTo("from-b");
    }

    /**
     * Closing the gateway closes what it opened.
     *
     * <p>It closed the hosted app and stopped the HTTP server, and left the client and the
     * executor: the client's connection pool and selector thread, and the virtual-thread executor
     * created inline and dropped. A host that restarts a gateway accumulated both.
     */
    @Test
    void closingTheGatewayReleasesItsClientAndExecutor() throws Exception {
        MultiAppGateway second = MultiAppGateway.start(installRoot, 0,
                MultiAppGateway.Mode.SUITE);
        java.net.http.HttpClient client = field(second, "client",
                java.net.http.HttpClient.class);
        java.util.concurrent.ExecutorService executor = field(second, "executor",
                java.util.concurrent.ExecutorService.class);
        assertThat(executor.isShutdown()).isFalse();

        second.close();

        assertThat(executor.isShutdown()).as("the server's executor").isTrue();
        assertThat(client.isTerminated()).as("the outbound HTTP client").isTrue();
    }

    /**
     * A client cannot present the header its target app trusts for mTLS.
     *
     * <p>A client certificate is public, so PKIX proves issuance rather than possession — the
     * edge's trust in the forwarded header *is* the control. The gateway forwarded a
     * client-supplied copy verbatim, so a caller could hand the app the very header it was
     * configured to believe. Only an edge in front of the gateway may set it.
     *
     * <p>{@code X-Tenant-Id} stays forwarded on purpose: the entitlement check here is a
     * convenience filter and the app's own tenancy resolution is the authoritative one
     * (docs/app-isolation-model.md decision 3), so stripping it would remove tenant context
     * from a request without adding a guarantee.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theTrustedMtlsHeaderIsStrippedOnIngress() throws Exception {
        Map<String, Set<String>> strip = field(gateway, "ingressStripByApp", Map.class);

        assertThat(strip.get("shop-a"))
                .as("the header shop-a's configuration tells it to trust")
                .containsExactly("x-client-cert");
        assertThat(strip.get("shop-a"))
                .as("the tenant header stays, by decision")
                .doesNotContain("x-tenant-id");
    }

    private static <T> T field(MultiAppGateway gateway, String name, Class<T> type)
            throws Exception {
        java.lang.reflect.Field field = MultiAppGateway.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(gateway));
    }

    /**
     * An oversized request body is refused rather than buffered.
     *
     * <p>The gateway read the whole body with {@code readAllBytes()} before forwarding it, so a
     * stranger decided how much of its heap to take. This is the front door: the app behind it
     * keeps whatever limits it declares, and the door has its own.
     */
    @Test
    void anOversizedBodyIsRefused() throws Exception {
        byte[] tooBig = new byte[MultiAppGateway.MAX_REQUEST_BODY_BYTES + 1024];

        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + gateway.port()
                                + "/apps/shop-a/api/items"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(tooBig))
                        .build(), java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void unknownAppReturns404() throws Exception {
        HttpResponse<String> response = get("/apps/nope/api/items");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("TQL-APP-4040");
    }

    @Test
    void enforcesTenantEntitlementAtTheFrontDoor() throws Exception {
        // shop-b is entitled to tenant-b only; a request declaring another tenant is refused
        // before it reaches the app, while the entitled tenant passes through.
        HttpResponse<String> denied = getWithTenant("/apps/shop-b/api/items", "tenant-x");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("TQL-APP-4030");

        HttpResponse<String> allowed = getWithTenant("/apps/shop-b/api/items", "tenant-b");
        assertThat(allowed.statusCode()).isEqualTo(200);

        // shop-a has no entitlement list, so every tenant is served.
        assertThat(getWithTenant("/apps/shop-a/api/items", "tenant-x").statusCode()).isEqualTo(200);
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
     * Isolated hosting addresses each app by its own hostname, and the shared-origin prefix is
     * not also live (docs/app-isolation-model.md decision 2).
     *
     * <p>Both addressings used to answer at once, so an operator who separated apps by hostname
     * — the reason to separate them being that a session must not cross — still had every app on
     * one origin through {@code /apps/<id>/}, where a session does cross. One mode, one address.
     */
    @Test
    void isolatedModeRoutesByHostAndRefusesTheSharedPrefix() throws Exception {
        try (MultiAppGateway isolated = MultiAppGateway.start(installRoot, 0,
                MultiAppGateway.Mode.ISOLATED)) {
            assertThat(itemNameForHost(isolated, "shop-a.localhost")).isEqualTo("from-a");
            assertThat(itemNameForHost(isolated, "shop-b.localhost")).isEqualTo("from-b");

            assertThat(statusOf(isolated, "/apps/shop-a/api/items"))
                    .as("the shared origin is not an address in isolated hosting")
                    .isEqualTo(404);
        }
    }

    /** And the converse: suite mode answers on the prefix and not on a hostname. */
    @Test
    void suiteModeRoutesByPrefixAndIgnoresHostnames() throws Exception {
        assertThat(itemName("shop-a")).isEqualTo("from-a");

        String response = rawGet(gateway, "/api/items", "shop-a.localhost");
        assertThat(response)
                .as("a hostname is not an address in suite mode")
                .startsWith("HTTP/1.1 404");
    }

    /**
     * An app with no hostname would be catalogued, started, and unreachable under isolated
     * hosting. Failing the start says so; a silent unreachable app does not.
     */
    @Test
    void isolatedModeRefusesAnAppWithNoHostname() throws Exception {
        Path root = Files.createTempDirectory("tesseraql-gw-addressless");
        try {
            new AppCatalog(root).register(
                    new InstalledApp("addressless", "1.0.0", "addressless/1.0.0", List.of()));

            assertThatThrownBy(() -> MultiAppGateway.start(root, 0, MultiAppGateway.Mode.ISOLATED))
                    .hasMessageContaining("addressless")
                    .hasMessageContaining("hostname");
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /**
     * A page rendered through the framework shell emits its URLs under the prefix, and they
     * answer.
     *
     * <p>This is what the base-path design exists for (docs/base-path.md). Before it, a page
     * under {@code /apps/<id>/} returned 200 with its stylesheet at {@code /assets/…} and
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
                        "http://localhost:" + gateway.port() + "/apps/shop-a/users")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);

        assertThat(page.body())
                .as("the shell's asset URLs carry the prefix")
                .contains("/apps/shop-a/assets/");
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
                .as("and the asset tree is not left mounted at the origin as well")
                .isEqualTo(404);
    }

    /** Every {@code href}/{@code src} in the page pointing into the asset tree, de-duplicated. */
    private static List<String> assetUrlsIn(String html) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:href|src)=\"(/apps/shop-a/assets/[^\"#]+)").matcher(html);
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }
        return List.copyOf(urls);
    }

    /**
     * The framework's own endpoints move under the prefix with the application's.
     *
     * <p>They are mounted by hand in the runtime's route builders — forty-seven
     * {@code rest().get("/_tesseraql/…")} calls across five classes — not through the
     * compiler's single mount point. Setting the prefix on Camel's context-wide REST
     * configuration reaches all of them at once (docs/base-path.md); concatenating it per route
     * would have had to find every one, and could have missed one silently.
     */
    @Test
    void frameworkEndpointsMoveUnderThePrefixToo() throws Exception {
        assertThat(statusOf(gateway, "/apps/shop-a/_tesseraql/health"))
                .as("a hand-mounted framework endpoint answers under the app's prefix")
                .isEqualTo(200);

        assertThat(statusOf(gateway, "/_tesseraql/health"))
                .as("and is not left exposed at the origin as well")
                .isEqualTo(404);
    }

    private static int statusOf(MultiAppGateway target, String path) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + target.port() + path)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String itemNameForHost(MultiAppGateway target, String hostName)
            throws Exception {
        String body = rawGet(target, "/api/items", hostName);
        int split = body.indexOf("\r\n\r\n");
        body = body.substring(split + 4);
        JsonNode data = MAPPER.readTree(body).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("name").asText();
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
        HttpResponse<String> response = get("/apps/" + appId + "/api/items");
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
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        Files.writeString(itemsDir.resolve("list.sql"), "select id, name from items order by id\n");

        new AppCatalog(installRoot).register(new InstalledApp(
                appId, "1.0.0", appId + "/1.0.0", entitledTenants, List.of(appId + ".localhost")));
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
