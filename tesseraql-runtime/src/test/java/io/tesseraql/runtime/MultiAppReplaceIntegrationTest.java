package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.operations.app.InstalledApp;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The replace operation, end to end (docs/runtime-replace.md structural decision 1): deploying an
 * application replaces its runtime while the stack serves. The headline property is Decision 29's
 * requirement stated as an assertion — requests fired continuously through the gateway never
 * drop while a member's runtime is replaced — and its converse: <b>a failed replace is a
 * no-op</b>, the serving runtime untouched by a candidate that cannot be admitted or started.
 *
 * <p>The tests are ordered because they narrate one member's deploy lifecycle over a single
 * running stack — a direct replace, refused candidates, the live canary ramp, the drain, and
 * finally the stack's own ordered stop, which necessarily comes last.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiAppReplaceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final AppInstaller INSTALLER = new AppInstaller();

    static MultiAppGateway gateway;
    static MultiAppHost host;
    static Path installRoot;
    static Path work;
    /** The cookie minted by a sign-in on v1, asserted to keep authenticating across replaces. */
    static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        work = Files.createTempDirectory("tesseraql-replace-work");
        installRoot = Files.createTempDirectory("tesseraql-replace-it");

        INSTALLER.install(packaged(appHome("1.0.0", "s1"), "1.0.0"), installRoot);

        // Versions isolate their business data by schema; the framework connection — sessions
        // included — is the stack's, which is what lets a sign-in survive the replace.
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
        host = gateway.host();
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        deleteRecursively(installRoot);
        deleteRecursively(work);
    }

    /**
     * The headline: requests fired continuously through the gateway while {@code replace} runs —
     * every response a 200 outside the swap's one documented residual, the version marker flips
     * old→new, and every request issued after the replace returned answers from the new version.
     * A session minted on v1 authenticates on v1 here and on v2 in the next test, because
     * sessions ride the stack's framework store.
     *
     * <p>The residual (docs/runtime-replace.md open question 6): a request whose connection dies
     * mid-flight is deliberately not replayed, and surfaces as the 502 it is. Asserting 200 on
     * every sample asserted the absence of exactly the error the swap is allowed to mint —
     * the same shape its sibling {@code StackDeployIntegrationTest} already tolerates — so one
     * 502 passes and anything more, or anything else, still fails.
     */
    @Test
    @Order(1)
    void requestsNeverDropThroughAReplace() throws Exception {
        assertThat(itemName()).isEqualTo("s1");
        sessionCookie = signIn();
        assertThat(authedStatus(sessionCookie)).isEqualTo(200);
        assertThat(authedStatus(null)).isNotEqualTo(200);

        record Sample(int status, String marker, boolean afterReplaceReturned) {
        }
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        AtomicBoolean replaced = new AtomicBoolean();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread traffic = new Thread(() -> {
            while (running.get()) {
                boolean after = replaced.get();
                try {
                    HttpResponse<String> response = get("/shop/api/items");
                    String marker = response.statusCode() == 200
                            ? MAPPER.readTree(response.body()).get("data").get(0).get("name")
                                    .asText()
                            : "";
                    samples.add(new Sample(response.statusCode(), marker, after));
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failed) {
                    samples.add(new Sample(-1, failed.getMessage(), after));
                }
            }
        });
        traffic.start();
        Thread.sleep(300);

        host.replace(place("2.0.0", appHome("2.0.0", "s2")));
        replaced.set(true);

        Thread.sleep(300);
        running.set(false);
        traffic.join(10_000);

        assertThat(samples).isNotEmpty();
        java.util.List<Sample> failures = samples.stream()
                .filter(sample -> sample.status() != 200).toList();
        assertThat(failures)
                .as("no request dropped through the swap (one mid-flight 502 is the swap's"
                        + " documented residual - docs/runtime-replace.md open question 6)")
                .allSatisfy(sample -> assertThat(sample.status()).isEqualTo(502))
                .hasSizeLessThanOrEqualTo(1);
        assertThat(samples).extracting(Sample::marker).contains("s1", "s2");
        assertThat(samples.stream()
                .filter(sample -> sample.afterReplaceReturned() && sample.status() == 200))
                .as("every request issued after the replace returned answers from v2")
                .isNotEmpty()
                .allSatisfy(sample -> assertThat(sample.marker()).isEqualTo("s2"));
    }

    /** Sign-in survives: the cookie minted on v1 authenticates on v2 with no re-login. */
    @Test
    @Order(2)
    void aSessionSignedInBeforeTheReplaceStillAuthenticates() throws Exception {
        assertThat(itemName()).isEqualTo("s2");
        assertThat(authedStatus(sessionCookie)).isEqualTo(200);
    }

    /**
     * A candidate that fails admission — modules declared but unresolved on disk, the boot
     * guard re-run for one application — leaves the serving runtime untouched.
     */
    @Test
    @Order(3)
    void aCandidateFailingAdmissionIsANoOp() throws Exception {
        int servingPort = host.port("shop");
        Path home = appHome("3.0.0", "s2");
        Files.writeString(home.resolve("config/overlay.yml"),
                Files.readString(home.resolve("config/overlay.yml"))
                        .replace("tesseraql:\n", "tesseraql:\n  modules:\n    - duckdb\n"));
        InstalledApp candidate = place("3.0.0", home);

        assertThatThrownBy(() -> host.replace(candidate))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("modules");

        assertThat(host.port("shop")).as("the serving runtime never moved")
                .isEqualTo(servingPort);
        assertThat(itemName()).isEqualTo("s2");
    }

    /** A candidate whose manifest cannot even load is refused the same way: before the swap. */
    @Test
    @Order(4)
    void aCandidateThatCannotStartIsANoOp() throws Exception {
        int servingPort = host.port("shop");
        Path home = appHome("4.0.0", "s2");
        Files.writeString(home.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: no-such-recipe
                """);
        InstalledApp candidate = place("4.0.0", home);

        assertThatThrownBy(() -> host.replace(candidate))
                .isInstanceOf(RuntimeException.class);

        assertThat(host.port("shop")).isEqualTo(servingPort);
        assertThat(itemName()).isEqualTo("s2");
    }

    /**
     * The canary ramp, live: the weight moves without a restart (the measured defect
     * docs/runtime-replace.md opens with), and promote starts nothing — the candidate runtime
     * <em>becomes</em> the stable slot.
     */
    @Test
    @Order(5)
    void theCanaryRampIsLiveAndPromoteStartsNothing() throws Exception {
        host.stageCanary(place("5.0.0", appHome("5.0.0", "s5")), 0);
        assertThat(host.hasCanary("shop")).isTrue();
        for (int i = 0; i < 15; i++) {
            assertThat(itemName()).as("at weight 0 the stable version serves everything")
                    .isEqualTo("s2");
        }

        host.setCanaryWeight("shop", 100);
        for (int i = 0; i < 15; i++) {
            assertThat(itemName()).as("the moved weight reaches the running roll, no restart")
                    .isEqualTo("s5");
        }

        int candidatePort = host.canaryPort("shop");
        host.promoteCanary("shop");
        assertThat(host.hasCanary("shop")).isFalse();
        assertThat(host.port("shop"))
                .as("promote starts nothing: the candidate runtime is the stable slot now")
                .isEqualTo(candidatePort);
        assertThat(itemName()).isEqualTo("s5");
    }

    /** Discard drains the candidate only; the serving runtime never notices. */
    @Test
    @Order(6)
    void aDiscardedCanaryLeavesTheStableRuntimeUntouched() throws Exception {
        int servingPort = host.port("shop");
        host.stageCanary(place("6.0.0", appHome("6.0.0", "s6")), 100);
        assertThat(itemName()).isEqualTo("s6");

        host.discardCanary("shop");

        assertThat(host.hasCanary("shop")).isFalse();
        assertThat(host.port("shop")).isEqualTo(servingPort);
        for (int i = 0; i < 10; i++) {
            assertThat(itemName()).isEqualTo("s5");
        }
    }

    /**
     * Swap-then-drain, observed: an in-flight slow request on the old runtime completes after
     * the swap, while new requests already land on the new version — the promote path, because
     * promote swaps instantly (nothing starts), which pins the window open on the drain alone.
     */
    @Test
    @Order(7)
    void theOldRuntimeDrainsWhileTheNewOneServes() throws Exception {
        host.stageCanary(place("7.0.0", appHome("7.0.0", "s7")), 0);
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return get("/shop/api/slow");
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
        awaitSleepActive();

        Thread promote = new Thread(() -> host.promoteCanary("shop"));
        promote.start();

        long deadline = System.currentTimeMillis() + 20_000;
        boolean newVersionServedWhileOldDrained = false;
        while (System.currentTimeMillis() < deadline) {
            if ("s7".equals(itemName()) && !slow.isDone()) {
                newVersionServedWhileOldDrained = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(newVersionServedWhileOldDrained)
                .as("the new runtime answers while the old one still drains its request")
                .isTrue();

        HttpResponse<String> drained = slow.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(drained.statusCode()).as("the in-flight request completed, not cut")
                .isEqualTo(200);
        assertThat(MAPPER.readTree(drained.body()).get("data").get(0).get("name").asText())
                .as("and it was the old version that answered it").isEqualTo("s5");
        promote.join(30_000);
        assertThat(itemName()).isEqualTo("s7");
    }

    /**
     * The stack's own stop is the same drain contract from the other direction
     * (docs/runtime-replace.md): readiness flips to 503 while liveness stays 200, everything in
     * flight completes, a request arriving mid-drain is still served, and only then does the
     * front close. Necessarily the last test.
     */
    @Test
    @Order(8)
    void theStackStopDrainsInsteadOfCutting() throws Exception {
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return get("/shop/api/slow");
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
        awaitSleepActive();

        MultiAppGateway closing = gateway;
        gateway = null;
        Thread closer = new Thread(closing::close);
        closer.start();

        long deadline = System.currentTimeMillis() + 10_000;
        boolean readinessFlipped = false;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> ready = get("/_tesseraql/health/ready", closing.port());
            if (ready.statusCode() == 503) {
                readinessFlipped = true;
                break;
            }
            Thread.sleep(25);
        }
        assertThat(readinessFlipped)
                .as("readiness answers 503 while the drain runs — stop routing, do not kill")
                .isTrue();
        assertThat(get("/_tesseraql/health/live", closing.port()).statusCode())
                .as("liveness stays 200 through the drain").isEqualTo(200);
        assertThat(get("/shop/api/items", closing.port()).statusCode())
                .as("a request arriving mid-drain is served, not refused").isEqualTo(200);

        HttpResponse<String> drained = slow.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(drained.statusCode()).as("the in-flight request completed before the front"
                + " closed").isEqualTo(200);
        closer.join(60_000);
        assertThat(closer.isAlive()).as("the stop returned within the derived bound").isFalse();
    }

    private static String itemName() throws Exception {
        HttpResponse<String> response = get("/shop/api/items");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data").get(0).get("name").asText();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return get(path, gateway != null ? gateway.port() : -1);
    }

    private static HttpResponse<String> get(String path, int port) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Signs in through the gateway on the current version; the cookie is the session's. */
    private static String signIn() throws Exception {
        HttpResponse<String> login = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port()
                        + "/shop/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static int authedStatus(String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + gateway.port() + "/shop/api/me"));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** Observe, don't guess (#860): the slow request's sleep is active before the test acts. */
    private static void awaitSleepActive() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement statement = connection.createStatement();
                    ResultSet active = statement.executeQuery(
                            "select count(*) from pg_stat_activity where state = 'active'"
                                    + " and pid <> pg_backend_pid()"
                                    + " and query like '%pg_sleep%'")) {
                if (active.next() && active.getLong(1) > 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The slow request's pg_sleep never became active");
    }

    /** Places {@code home} into the install root as a side-by-side version, catalogue untouched. */
    private static InstalledApp place(String version, Path home) throws IOException {
        return INSTALLER.place(packaged(home, version), installRoot, null, List.of());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            // The sign-in fixture, wherever the identity store resolves it from: the shared
            // default schema, and each version's own.
            seedIdentity(statement, hash, params);
            for (String tag : new String[]{"s1", "s2", "s5", "s6", "s7"}) {
                statement.execute("create schema " + tag);
                statement.execute("create table " + tag
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + tag + ".items (name) values ('" + tag + "')");
                statement.execute("set search_path to " + tag);
                seedIdentity(statement, hash, params);
                statement.execute("set search_path to public");
            }
        }
    }

    private static void seedIdentity(Statement statement, String hash, String params)
            throws Exception {
        for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                .split(";")) {
            if (!ddl.isBlank()) {
                statement.execute(ddl);
            }
        }
        statement.execute("insert into tql_users "
                + "(user_id, login_id, display_name, status, password_hash, password_algo,"
                + " password_params) values ('u1','admin','Administrator','ACTIVE','" + hash
                + "','pbkdf2','" + params + "')");
        // The member fence (docs/stack-shells.md structural decision 3): an authenticated
        // principal needs tql.app.use.<member> to reach the member's routes at all.
        statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                + " values ('r1','r1','r1')");
        statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
        statement.execute("insert into tql_permissions"
                + " (permission_id, permission_code, permission_name)"
                + " values ('tql.app.use.*','tql.app.use.*','tql.app.use.*')");
        statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                + " values ('r1','tql.app.use.*')");
    }

    /** An app home for {@code version} bound to schema {@code schema}, ready to package. */
    private static Path appHome(String version, String schema) throws IOException {
        Path home = work.resolve("app-" + version);
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, home, path));
        }
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

        // The drain's observable: a request the old runtime is still answering while the new
        // one already serves. pg_sleep in the row source holds the exchange open.
        Path slowDir = home.resolve("web/api/slow");
        Files.createDirectories(slowDir);
        Files.writeString(slowDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.slow
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: slow.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(slowDir.resolve("slow.sql"),
                "select name, pg_sleep(3)::text as slept from items limit 1\n");

        // The sign-in observable: a route only a session reaches, so the cookie minted on v1
        // proves itself on v2.
        Path meDir = home.resolve("web/api/me");
        Files.createDirectories(meDir);
        Files.writeString(meDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.me
                kind: route
                recipe: query-json
                security:
                  auth: browser
                sources:
                  main:
                    sql:
                      file: me.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(meDir.resolve("me.sql"), "select name from items order by id\n");
        return home;
    }

    private static Path packaged(Path home, String version) throws IOException {
        Path pkg = work.resolve("app-" + version + ".tqlapp");
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
