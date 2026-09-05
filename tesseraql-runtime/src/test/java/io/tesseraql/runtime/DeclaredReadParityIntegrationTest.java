package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The honesty probe: a {@code sql:} route and a {@code contract:} route over byte-identical
 * statement text must answer alike.
 *
 * <p>This is the instrument {@code docs/route-governance-parity.md} specified as
 * {@code SqlExecutionContract} and never built — the identifier appeared at exactly one place in
 * the repository, that doc line. It is the guard that would have caught all four of the retrofits
 * this campaign catalogued, because each of them was a route contract quietly answering
 * differently from the route statement beside it: the statement timeout, tracing, declarative
 * pagination, and the row bound.
 *
 * <p><b>The assertions are on the two HTTP responses, never on compiler source.</b> That is
 * deliberate and it is the whole design. Since the compiler's contract branch was deleted, both
 * arms are built by one construction expression — so "both compile to a SqlStep with the same
 * bounds" would restate that one line and stay green however the two arms actually behaved. What
 * remains genuinely divergent is what a source declares, and that only shows up in what a request
 * gets back.
 *
 * <p>The realm is a {@code type: sql} realm, not the managed default. A managed realm reads its
 * contracts from a classpath pack, which would hide exactly the application-home behaviour a
 * deployment's own realm depends on.
 */
@Testcontainers
class DeclaredReadParityIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One statement, and both routes run this text. */
    private static final String LIST_THINGS = """
            select
              t.thing_id    as thing_id,
              t.label       as label,
              t.occurred_at as occurred_at
            from
              parity_things t
            order by
              t.thing_id
            """;

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        seedDatabase();
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

    /**
     * A JDBC timestamp renders the same way on both arms.
     *
     * <p>It did not. The uncapped reader behind the contract path returned the driver's own
     * temporal while every route read passed values through {@code ResultRows}, so one store
     * answered the same column two ways depending on which arm asked.
     */
    @Test
    void bothArmsShapeATemporalTheSameWay() throws Exception {
        JsonNode sqlRows = rows(get("/api/parity/sql"));
        JsonNode contractRows = rows(get("/api/parity/contract"));

        assertThat(sqlRows.get(0).get("occurred_at").asText())
                .isEqualTo(contractRows.get(0).get("occurred_at").asText())
                .isEqualTo("2026-09-05T14:30:00Z");
    }

    /** And the row shape itself: the same keys, in the same order, from the same statement. */
    @Test
    void bothArmsPublishTheSameRowKeys() throws Exception {
        assertThat(keysOf(rows(get("/api/parity/sql")).get(0)))
                .isEqualTo(keysOf(rows(get("/api/parity/contract")).get(0)))
                .containsExactly("thing_id", "label", "occurred_at");
    }

    /**
     * A per-binding {@code materialize:} bound refuses on both arms, with the same code and the
     * same status.
     *
     * <p>Two things had to be true for this to pass, and neither was: the row bound had to reach a
     * contract read at all, and a contract binding had to be able to declare one.
     */
    @Test
    void bothArmsRefuseAtTheSameDeclaredBound() throws Exception {
        HttpResponse<String> sql = get("/api/parity/sql-bounded");
        HttpResponse<String> contract = get("/api/parity/contract-bounded");

        assertThat(contract.statusCode()).isEqualTo(sql.statusCode()).isEqualTo(500);
        assertThat(codeOf(contract)).isEqualTo(codeOf(sql)).isEqualTo("TQL-LD-0001");
    }

    /** Under {@code onOverflow: warn} both truncate to the same rows rather than refusing. */
    @Test
    void bothArmsTruncateAlikeUnderWarn() throws Exception {
        JsonNode sqlRows = rows(get("/api/parity/sql-warn"));
        JsonNode contractRows = rows(get("/api/parity/contract-warn"));

        assertThat(sqlRows).hasSize(2);
        assertThat(contractRows).hasSize(2);
        assertThat(contractRows.toString()).isEqualTo(sqlRows.toString());
    }

    /** Offset pagination publishes the same page metadata on both arms. */
    @Test
    void bothArmsPublishTheSamePageKeys() throws Exception {
        JsonNode sqlPage = MAPPER.readTree(get("/api/parity/sql-paged").body()).path("page");
        JsonNode contractPage = MAPPER.readTree(get("/api/parity/contract-paged").body())
                .path("page");

        // Anchored to the actual keys, not only to each other: two arms that both published
        // nothing would agree perfectly, and this test exists to catch agreement that is real.
        assertThat(keysOf(sqlPage)).contains("number", "size", "hasNext", "hasPrev");
        assertThat(keysOf(contractPage)).isEqualTo(keysOf(sqlPage));
        assertThat(contractPage.toString()).isEqualTo(sqlPage.toString());
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private JsonNode rows(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("data");
    }

    private String codeOf(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body()).path("error").path("code").asText();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists parity_things ("
                    + " thing_id varchar(16) primary key,"
                    + " label varchar(64) not null,"
                    + " occurred_at timestamp not null)");
            statement.execute("truncate table parity_things");
            statement.execute("insert into parity_things (thing_id, label, occurred_at) values"
                    + " ('t1','one', timestamp '2026-09-05 14:30:00'),"
                    + " ('t2','two', timestamp '2026-09-05 14:31:00'),"
                    + " ('t3','three', timestamp '2026-09-05 14:32:00')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-parity-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(target);
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // A sql realm, so the contract arm resolves from the application's own home rather than
        // from the managed pack's classpath resources. The app declares a managed realm, so this
        // replaces that block rather than appending a second one.
        Path config = target.resolve("config/tesseraql.yml");
        String declared = Files.readString(config);
        String managedRealm = """
                  identity:
                    defaultRealm: local
                    realms:
                      local:
                        type: managed
                        datasource: main
                """;
        if (!declared.contains(managedRealm)) {
            throw new IllegalStateException("the example app's identity block moved; "
                    + "this fixture replaces it and must be updated with it");
        }
        Files.writeString(config, declared.replace(managedRealm, """
                  identity:
                    defaultRealm: parity
                    realms:
                      parity:
                        type: sql
                        datasource: main
                """));

        // The contract, and the colocated file. Byte-identical text is the point of the fixture.
        Path realm = target.resolve("security/identity/parity");
        Files.createDirectories(realm);
        Files.writeString(realm.resolve("list-things.sql"), LIST_THINGS);
        Path web = target.resolve("web/api/parity");
        Files.createDirectories(web);

        route(web, "sql", sqlArm("", ""));
        route(web, "contract", contractArm("", ""));
        route(web, "sql-bounded", sqlArm(BOUND, ""));
        route(web, "contract-bounded", contractArm(BOUND, ""));
        route(web, "sql-warn", sqlArm(WARN, ""));
        route(web, "contract-warn", contractArm(WARN, ""));
        route(web, "sql-paged", sqlArm("", PAGED));
        route(web, "contract-paged", contractArm("", PAGED));
        return target;
    }

    /** The bound both bounded routes declare, identically. */
    private static final String BOUND = "      materialize:\n        maxRows: 2\n";

    /** The same, truncating instead of refusing. */
    private static final String WARN = "      materialize:\n        maxRows: 2\n        onOverflow: warn\n";

    /** The pagination block both paged routes declare, identically. */
    private static final String PAGED = "input:\n  page:\n    type: integer\n    required: false\n"
            + "pagination:\n  strategy: offset\n  size: 2\n";

    private static String sqlArm(String bounds, String extra) {
        return extra + "sources:\n  main:\n    sql:\n      file: list-things.sql\n" + bounds;
    }

    private static String contractArm(String bounds, String extra) {
        return extra
                + "sources:\n  main:\n    contract:\n      name: identity.list-things\n"
                + bounds;
    }

    /**
     * One route, written the same way for both arms so the only difference is the source block.
     * Assembled by concatenation rather than by a formatted text block, because YAML is
     * indentation-sensitive and a substituted block carries its own.
     */
    private static void route(Path web, String name, String body) throws IOException {
        Path dir = web.resolve(name);
        Files.createDirectories(dir);
        // A file: is resolved against the route's own directory, so each sql arm gets the same
        // text beside it - which is the fixture's point: byte-identical statements, two arms.
        if (name.startsWith("sql")) {
            Files.writeString(dir.resolve("list-things.sql"), LIST_THINGS);
        }
        StringBuilder route = new StringBuilder()
                .append("version: tesseraql/v1\n")
                .append("id: parity.").append(name.replace('-', '.')).append("\n")
                .append("kind: route\n")
                .append("recipe: query-json\n\n")
                .append("security:\n  policy: users.read\n\n")
                .append(body)
                .append("\nresponse:\n  json:\n    status: 200\n    body:\n")
                .append("      data: main.rows\n");
        if (name.endsWith("paged")) {
            // The step publishes its page metadata into the context; a response that never names
            // it renders nothing, and two arms rendering nothing would agree perfectly.
            route.append("      page: page\n");
        }
        Files.writeString(dir.resolve("get.yml"), route.toString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(
                MAPPER.writeValueAsBytes(
                        TestClaims.addressed(Map.of("sub", "u1", "roles", List.of("USER_READ")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
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
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
