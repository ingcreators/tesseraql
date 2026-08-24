package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.version.SemanticVersion;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.operations.app.AppUpgrader;
import io.tesseraql.operations.app.InstalledApp;
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
import java.util.List;
import java.util.function.BooleanSupplier;
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
 * The file protocol end to end (docs/runtime-replace.md structural decision 2): the same
 * {@code catalog.json} and {@code .upgrade/} writes the CLI's deploy lifecycle performs, against
 * a running gateway — the reconciler converges the host to each one while the stack serves, and
 * a restart mid-canary boots exactly the arrangement the reconciler had built, because boot and
 * live are one function of the same files.
 *
 * <p>Ordered: the tests narrate one deploy lifecycle — upgrade, canary, weight, promote,
 * rollback, a refused candidate, and the restart.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackReconcilerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final AppUpgrader UPGRADER = new AppUpgrader();
    private static final SemanticVersion FRAMEWORK = SemanticVersion.parse("0.1.0");
    private static final long CONVERGE_MILLIS = 60_000;

    static MultiAppGateway gateway;
    static Path installRoot;
    static Path work;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        work = Files.createTempDirectory("tesseraql-reconcile-work");
        installRoot = Files.createTempDirectory("tesseraql-reconcile-it");

        new AppInstaller().install(packageApp("1.0.0", "s1"), installRoot);
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

    /** A direct upgrade written to disk replaces the runtime, and the host reports it back. */
    @Test
    @Order(1)
    void aDirectUpgradeOnDiskReplacesTheRuntime() throws Exception {
        assertThat(itemName()).isEqualTo("s1");

        UPGRADER.upgrade(packageApp("2.0.0", "s2"), installRoot, FRAMEWORK);

        await("the catalogue's new version serves", () -> "s2".equals(itemNameQuietly()));
        await("the applied outcome lands in the status file",
                () -> statusFile().contains("\"applied\"") && statusFile().contains("2.0.0"));
    }

    /** A staged canary appears at its written weight, and a weight edit reaches the live roll. */
    @Test
    @Order(2)
    void aStagedCanaryAppearsAndItsWeightIsLive() throws Exception {
        UPGRADER.upgrade(packageApp("3.0.0", "s3"), installRoot, FRAMEWORK, true);

        await("the staged candidate is hosted at its weight",
                () -> gateway.host().hasCanary("shop")
                        && gateway.host().canaryWeight("shop") == 10);
        assertThat(itemName()).isIn("s2", "s3");

        UPGRADER.setCanaryWeight("shop", installRoot, 100);
        await("the moved weight reaches the running roll",
                () -> gateway.host().canaryWeight("shop") == 100);
        await("all traffic lands on the candidate", () -> "s3".equals(itemNameQuietly()));
    }

    /** A promote on disk is the catalogue moving onto the canary's version: nothing starts. */
    @Test
    @Order(3)
    void aPromoteOnDiskSwapsWithoutStartingAnything() throws Exception {
        int candidatePort = gateway.host().canaryPort("shop");

        UPGRADER.promote("shop", installRoot);

        await("the candidate runtime becomes the stable slot",
                () -> !gateway.host().hasCanary("shop")
                        && gateway.host().port("shop") == candidatePort);
        assertThat(itemName()).isEqualTo("s3");
    }

    /** A rollback is the catalogue moving back onto files still on disk: a plain replace. */
    @Test
    @Order(4)
    void aRollbackOnDiskReturnsThePreviousVersion() throws Exception {
        UPGRADER.rollback("shop", installRoot);

        await("the previous version serves again", () -> "s2".equals(itemNameQuietly()));
    }

    /**
     * A candidate that fails admission is recorded in the status file with the refusal's own
     * message, and the serving runtime never notices — failure does not loop, it waits for the
     * operator's next write.
     */
    @Test
    @Order(5)
    void aRefusedCandidateIsRecordedAndTheStackKeepsServing() throws Exception {
        Path home = appHome("4.0.0", "s2");
        Files.writeString(home.resolve("config/overlay.yml"),
                Files.readString(home.resolve("config/overlay.yml"))
                        .replace("tesseraql:\n", "tesseraql:\n  modules:\n    - duckdb\n"));
        InstalledApp candidate = new AppInstaller().place(packaged(home, "4.0.0"), installRoot,
                null, List.of());
        Files.writeString(installRoot.resolve(".upgrade/shop.json"),
                "{\"previous\":null,\"candidate\":" + MAPPER.writeValueAsString(candidate)
                        + ",\"canaryWeight\":10}");

        await("the refusal lands in the status file with its own message",
                () -> statusFile().contains("\"refused\"")
                        && statusFile().contains("modules"));
        assertThat(gateway.host().hasCanary("shop")).isFalse();
        assertThat(itemName()).isEqualTo("s2");

        // The operator's next write: clearing the intent converges to "nothing staged".
        Files.delete(installRoot.resolve(".upgrade/shop.json"));
    }

    /**
     * The other read point of the same files: kill the gateway mid-canary, restart, and boot
     * builds exactly the arrangement the reconciler had converged to.
     */
    @Test
    @Order(6)
    void aRestartMidCanaryBootsWhatTheReconcilerBuilt() throws Exception {
        UPGRADER.upgrade(packageApp("5.0.0", "s5"), installRoot, FRAMEWORK, true);
        UPGRADER.setCanaryWeight("shop", installRoot, 37);
        await("the canary runs at its written weight",
                () -> gateway.host().hasCanary("shop")
                        && gateway.host().canaryWeight("shop") == 37);

        gateway.close();
        gateway = MultiAppGateway.start(installRoot, 0);

        assertThat(gateway.host().hasCanary("shop")).isTrue();
        assertThat(gateway.host().canaryWeight("shop")).isEqualTo(37);
        assertThat(itemName()).isIn("s2", "s5");
    }

    /** Membership stays start-time: a name added to the catalogue starts nothing. */
    @Test
    @Order(7)
    void aNewCatalogueNameStartsNothingWhileRunning() throws Exception {
        new AppCatalog(installRoot)
                .register(new InstalledApp("other", "1.0.0", "other/1.0.0", List.of()));
        // Converge on an unrelated write so the pass provably ran after the edit.
        UPGRADER.setCanaryWeight("shop", installRoot, 40);
        await("the pass after the membership edit ran",
                () -> gateway.host().canaryWeight("shop") == 40);

        assertThat(gateway.host().appNames()).containsExactly("shop");
    }

    private static String statusFile() {
        try {
            Path status = installRoot.resolve(".upgrade/shop.status.json");
            return Files.isRegularFile(status) ? Files.readString(status) : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + CONVERGE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static String itemName() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gateway.port() + "/shop/api/items")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data").get(0).get("name").asText();
    }

    private static String itemNameQuietly() {
        try {
            return itemName();
        } catch (Throwable notYet) {
            return "";
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String tag : new String[]{"s1", "s2", "s3", "s5"}) {
                statement.execute("create schema " + tag);
                statement.execute("create table " + tag
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + tag + ".items (name) values ('" + tag + "')");
            }
        }
    }

    private static Path packageApp(String version, String schema) throws IOException {
        return packaged(appHome(version, schema), version);
    }

    /** An app home for {@code version} bound to schema {@code schema}, ready to package. */
    private static Path appHome(String version, String schema) throws IOException {
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
