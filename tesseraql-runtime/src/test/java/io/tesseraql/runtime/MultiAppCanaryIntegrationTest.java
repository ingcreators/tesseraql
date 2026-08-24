package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.version.SemanticVersion;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.operations.app.AppUpgrader;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
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
 * Integration test for canary traffic splitting (design ch. 31). A staged canary candidate is
 * hosted alongside the stable version and the gateway splits requests between them by the configured
 * weight, so both versions are exercised.
 */
@Testcontainers
class MultiAppCanaryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static MultiAppGateway gateway;
    static Path installRoot;
    static Path work;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        work = Files.createTempDirectory("tesseraql-canary-work");
        installRoot = Files.createTempDirectory("tesseraql-canary-it");

        Path stable = packageApp("1.0.0", "stable");
        Path candidate = packageApp("2.0.0", "canary");
        new AppInstaller().install(stable, installRoot);

        AppUpgrader upgrader = new AppUpgrader();
        upgrader.upgrade(candidate, installRoot, SemanticVersion.parse("0.1.0"), true);
        upgrader.setCanaryWeight("shop", installRoot, 50);

        // Stable and canary isolate their business data by schema, so their main coordinates
        // differ — and without a shared framework connection the canary would be refused with
        // TQL-APP-4214, correctly: a session signed in on stable would die on the canary leg.
        // The stack supplies the framework connection both versions ride.
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
        deleteRecursively(installRoot);
        deleteRecursively(work);
    }

    @Test
    void splitsTrafficBetweenStableAndCanary() throws Exception {
        Set<String> served = new HashSet<>();
        for (int i = 0; i < 40 && served.size() < 2; i++) {
            served.add(itemName());
        }
        assertThat(served).containsExactlyInAnyOrder("stable", "canary");
    }

    /**
     * The ops shell shows a staged member twice — the stable entry and the canary's own —
     * because runtime-local data is exactly what an operator watches a ramp for, and neither a
     * weighted roll nor a stable pin can show the canary's ring on purpose
     * (docs/stack-shells.md structural decision 2). Addressing is proved by acting: a job run
     * on the canary slot lands in the canary runtime's own store, so the stable slot's pages
     * read that execution as unknown while the canary's show it.
     */
    @Test
    void theShellShowsTheCanaryAsASecondEntryAndAddressesItsSlot() throws Exception {
        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port()
                        + "/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"operator\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        String csrf = MAPPER.readTree(login.body()).path("csrfToken").asText();

        String home = shellGet("/_tesseraql/ops/console", cookie).body();
        assertThat(home).contains(">shop<").contains(">shop (canary)<")
                .contains("/_tesseraql/ops/console/shop?slot=canary");

        HttpResponse<String> started = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port()
                                + "/_tesseraql/ops/console/shop/jobs/run"))
                                .header("Cookie", cookie)
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "id=user.dailyMaintenance&slot=canary&_csrf=" + csrf))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(303);
        String location = started.headers().firstValue("location").orElseThrow();
        assertThat(location).contains("/_tesseraql/ops/console/shop/executions/")
                .contains("slot=canary");

        // The canary slot shows its own execution…
        assertThat(shellGet(location, cookie).body()).contains("Job started.");
        // …and the stable slot reads the same id as unknown: two runtimes, two rings/stores.
        String stableLocation = location.replace("&slot=canary", "").replace("?slot=canary", "");
        assertThat(shellGet(stableLocation, cookie).body()).contains("Execution not found.");
    }

    private static HttpResponse<String> shellGet(String path, String cookie) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gateway.port() + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String itemName() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gateway.port() + "/shop/api/items")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data").get(0).get("name").asText();
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String tag : new String[]{"stable", "canary"}) {
                statement.execute("create schema " + tag);
                statement.execute("create table " + tag
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + tag + ".items (name) values ('" + tag + "')");
            }
            // The ops shell's operator: signs in at the origin against the shared framework
            // store, granted the full atoms (docs/stack-shells.md structural decision 1).
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            String hash = new io.tesseraql.security.password.Pbkdf2PasswordEncoder()
                    .encode("s3cret");
            String params = new io.tesseraql.security.password.Pbkdf2PasswordEncoder()
                    .defaultParams();
            statement.execute("insert into tql_users "
                    + "(user_id, login_id, display_name, status, password_hash, password_algo,"
                    + " password_params) values ('u-op','operator','Operator','ACTIVE','" + hash
                    + "','pbkdf2','" + params + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-op','r-op','r-op')");
            statement.execute(
                    "insert into tql_user_roles (user_id, role_id) values ('u-op','r-op')");
            for (String permission : new String[]{"tql.ops.view.*", "tql.ops.run.*"}) {
                statement.execute("insert into tql_permissions"
                        + " (permission_id, permission_code, permission_name) values ('"
                        + permission + "','" + permission + "','" + permission + "')");
                statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                        + " values ('r-op','" + permission + "')");
            }
        }
    }

    /** Builds an app-home for version {@code version} bound to schema {@code schema}, zipped to a package. */
    private static Path packageApp(String version, String schema) throws IOException {
        Path home = work.resolve("app-" + version);
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, home, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(home);
        // The overlay renames the copied example to `shop`, so its permission codes must carry
        // that name too (TQL-YAML-1406): a code is `<app>.<what>`.
        Path exampleConfig = home.resolve("config/tesseraql.yml");
        Files.writeString(exampleConfig, Files.readString(exampleConfig)
                .replace("permission: user-admin.", "permission: shop."));
        Files.writeString(home.resolve("config/overlay.yml"), """
                tesseraql:
                  app:
                    name: shop
                    version: %s
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                """.formatted(version, POSTGRES.getJdbcUrl(), schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path itemsDir = home.resolve("web/api/items");
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

        Path pkg = work.resolve("app-" + version + ".tqlapp");
        zip(home, pkg);
        return pkg;
    }

    private static void zip(Path home, Path out) throws IOException {
        try (OutputStream stream = Files.newOutputStream(out);
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
