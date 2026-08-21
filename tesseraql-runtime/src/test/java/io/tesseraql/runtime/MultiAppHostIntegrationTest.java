package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Integration test for runtime multi-app hosting (design ch. 32.7). Two installed apps catalogued
 * under one install root are hosted simultaneously, each isolated in its own runtime, port, and
 * database schema; each app serves only its own data.
 */
@Testcontainers
class MultiAppHostIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static MultiAppHost host;
    static Path installRoot;
    /** The fixed internal port shop-a declares, chosen free at fixture time (decision 4a). */
    static int declaredPort;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-multiapp-it");
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            declaredPort = probe.getLocalPort();
        }
        installApp("shop-a", "a", null, ModuleFixtureFunctions.GreetsA.class);
        // shop-b declares a base path of its own, which the derived address has to outrank —
        // an application's address is its name, and its own configuration cannot move it.
        installApp("shop-b", "b", "/legacy", ModuleFixtureFunctions.GreetsB.class);
        // Business data is isolated by schema, so the main coordinates differ; the stack
        // supplies the framework connection (docs/stack-architecture.md decision 22).
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
        host = MultiAppHost.start(installRoot);
    }

    @AfterAll
    static void stop() throws IOException {
        if (host != null) {
            host.close();
        }
        if (installRoot != null) {
            deleteRecursively(installRoot);
        }
    }

    @Test
    void hostsBothAppsEachServingOwnData() throws Exception {
        assertThat(host.appNames()).containsExactlyInAnyOrder("shop-a", "shop-b");

        assertThat(itemName("shop-a", "/shop-a")).isEqualTo("from-a");
        assertThat(itemName("shop-b", "/shop-b")).isEqualTo("from-b");
    }

    /**
     * Every runtime in the host serves on one Vert.x instance (docs/http-threading.md decision 4).
     *
     * <p>Each built its own. {@code VertxPlatformHttpServer} looks a Vert.x up in the runtime's own
     * Camel registry and builds one when it finds none, so a host's worker and event-loop threads
     * were a function of how many applications were installed — five applications on a twenty-core
     * machine meant a hundred worker threads and two hundred event loops, and no configuration
     * reduced it.
     *
     * <p>Asserted as identity rather than by counting threads: a thread census would be measuring
     * whatever else shares the test JVM, and the property that matters is that these two runtimes
     * are looking at the same object.
     */
    @Test
    void everyRuntimeSharesTheHostsOneVertx() {
        io.vertx.core.Vertx a = vertxOf("shop-a");
        io.vertx.core.Vertx b = vertxOf("shop-b");

        assertThat(a).isNotNull();
        assertThat(b).isSameAs(a);
    }

    private static io.vertx.core.Vertx vertxOf(String appId) {
        return host.app(appId).context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.VERTX_BEAN,
                io.vertx.core.Vertx.class);
    }

    /**
     * Decision 28's headline (docs/module-scope.md): both applications declare a module
     * providing {@code shopgreets()} — same name, different semantics — and each answers with
     * its own. Under the retired process-global registry the last install replaced its
     * neighbour's function; under one union loader the answer depended on classpath order.
     */
    @Test
    void eachApplicationEvaluatesItsOwnModuleFunctions() throws Exception {
        assertThat(greeting("shop-a", "/shop-a")).isEqualTo("from-module-a");
        assertThat(greeting("shop-b", "/shop-b")).isEqualTo("from-module-b");
    }

    private static String greeting(String appId, String prefix) throws Exception {
        HttpResponse<String> response = get(appId, prefix + "/api/greet");
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data").get(0).get("greeting").asText();
    }

    /**
     * The host also starts the stack surface runtime — the origin scope's sign-in, account
     * surface and portal (docs/root-portal.md). It is not a member: it has no name in
     * {@link MultiAppHost#appNames()}, and it rides the stack's framework coordinate, so its
     * {@code security} validation passing is what proves it joined the same schema the host
     * migrated.
     */
    @Test
    void theStackSurfaceServesTheOriginScope() throws Exception {
        assertThat(host.appNames()).doesNotContain("portal", "#portal");

        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + host.surfacePort()
                        + "/_tesseraql/login")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("action=\"/_tesseraql/login\"");
    }

    /**
     * The address is derived from the name and the host starts the runtime serving it, so the app
     * answers on its own port at the same path the gateway forwards (docs/base-path.md decision 5).
     * Hosting used to leave the prefix to the caller, and this entry point passed none at all.
     */
    @Test
    void anAppIsStartedServingTheAddressDerivedFromItsName() throws Exception {
        assertThat(get("shop-a", "/api/items").statusCode()).isEqualTo(404);
        assertThat(get("shop-a", "/shop-a/api/items").statusCode()).isEqualTo(200);
    }

    /**
     * The derived address outranks the application's own {@code tesseraql.http.basePath}
     * (docs/stack-architecture.md Decision 25): an application's configuration cannot move where
     * it answers, or the gateway would forward it paths it does not serve — a 404 on every
     * request, invisibly.
     */
    @Test
    void theDerivedAddressOutranksTheApplicationsOwnBasePath() throws Exception {
        assertThat(get("shop-b", "/legacy/api/items").statusCode()).isEqualTo(404);
        assertThat(get("shop-b", "/shop-b/api/items").statusCode()).isEqualTo(200);
    }

    /**
     * A declared {@code server.port} is honoured as the application's internal port
     * (docs/cli-surface.md decision 4a): the key keeps its one meaning — the port this
     * application binds — while the front door stays the gateway's {@code --port}. shop-b
     * declares {@code 0}, the fixtures' pre-hosting idiom for "ephemeral", and stays ephemeral.
     */
    @Test
    void aDeclaredServerPortIsTheApplicationsInternalPort() {
        assertThat(host.port("shop-a")).isEqualTo(declaredPort);
        assertThat(host.port("shop-b")).isNotEqualTo(declaredPort);
    }

    /**
     * A hosted runtime validates the {@code security} schema instead of migrating it, so a
     * runtime pointed at a framework datasource the host never migrated fails loudly at boot —
     * the wrong-framework-datasource guard (docs/stack-architecture.md decision 16). The rest of
     * this class is the positive half: both runtimes above validated the schema the host
     * migrated, or nothing here would have started.
     */
    @Test
    void anUnmigratedFrameworkDatasourceIsRefusedNotMigrated() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create schema fw_empty");
        }
        try (com.zaxxer.hikari.HikariDataSource unmigrated = DataSources.create(
                "tesseraql-test-wrong-framework",
                new DataSources.MainDatasourceOverride(
                        POSTGRES.getJdbcUrl() + "&currentSchema=fw_empty",
                        POSTGRES.getUsername(), POSTGRES.getPassword()))) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> FrameworkMigrations.validateSecurity(unmigrated))
                    .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                    .hasMessageContaining("TQL-APP-4214")
                    .hasMessageContaining("validates instead of migrating");
        }
    }

    private static String itemName(String appId, String prefix) throws Exception {
        HttpResponse<String> response = get(appId, prefix + "/api/items");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(response.body()).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("name").asText();
    }

    private static HttpResponse<String> get(String appId, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + host.port(appId) + path)).build(),
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

    /**
     * Installs a copy of the example app under {@code appId}, bound to the given DB schema.
     *
     * @param ownBasePath the prefix the application's own configuration names, or null — the
     *                    derived address outranks it either way
     */
    private static void installApp(String appId, String schema, String ownBasePath,
            Class<?> functionProvider) throws IOException {
        Path appHome = installRoot.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: %d
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                tesseraql:
                  modules:
                    - io.example:greeter
                """.formatted("shop-a".equals(appId) ? declaredPort : 0,
                POSTGRES.getJdbcUrl(), schema,
                POSTGRES.getUsername(), POSTGRES.getPassword())
                + (ownBasePath == null ? "" : """
                          http:
                            basePath: %s
                        """.formatted(ownBasePath)));
        writeModuleJar(appHome, functionProvider);

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

        // A route whose SQL calls the module-provided function: the decision-28 headline. Both
        // applications use the same name, and each must answer with its own module's semantics.
        Path greetDir = appHome.resolve("web/api/greet");
        Files.createDirectories(greetDir);
        Files.writeString(greetDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: greet.function
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: greet.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(greetDir.resolve("greet.sql"),
                "select /* shopgreets() */ 'placeholder' as greeting\n");

        new AppCatalog(installRoot).register(
                new InstalledApp(appId, "1.0.0", appId + "/1.0.0", List.of()));
    }

    /**
     * A module jar holding only the {@code META-INF/services} entry: the provider class sits on
     * the test classpath (the parent loader), so which implementation an application's registry
     * holds is decided entirely by the services file in its own {@code work/modules} jar.
     */
    private static void writeModuleJar(Path appHome, Class<?> providerClass) throws IOException {
        Path modules = appHome.resolve("work/modules");
        Files.createDirectories(modules);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(modules.resolve("greeter.jar")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(
                    "META-INF/services/io.tesseraql.core.expr.ExpressionFunction"));
            zip.write((providerClass.getName() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
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
