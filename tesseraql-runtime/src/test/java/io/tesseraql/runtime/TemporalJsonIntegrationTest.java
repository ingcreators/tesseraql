package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a temporal or vendor column becomes on a JSON route (docs/temporal-semantics.md T0): a
 * wall clock without a zone designator, an instant at UTC, a time with its offset, a driver's
 * wrapper as its text — and the same text under two JVM zones. Before the read seam, a DuckDB
 * {@code date}, {@code time} or {@code timestamptz} answered 500 (a {@code java.time} value
 * reached a JSON mapper with no module for it), a PostgreSQL zoneless {@code timestamp} was
 * served as a UTC instant built in the JVM's zone, {@code timetz} was served host-zoned with
 * its offset dropped, and {@code jsonb}/{@code interval} were served as the driver's bean.
 */
@Testcontainers
class TemporalJsonIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The headline: the DuckDB analytics stack's most ordinary columns answer at all. */
    @Test
    void aDuckDbTemporalColumnAnswersInsteadOf500() throws Exception {
        assertThat(first("/api/duck/d")).isEqualTo("2026-01-15");
        assertThat(first("/api/duck/t")).isEqualTo("22:30:00.5");
        assertThat(first("/api/duck/tstz")).isEqualTo("2026-01-15T22:30:00.123456Z");
        assertThat(first("/api/duck/ts")).isEqualTo("2026-01-15T22:30:00.123456");
    }

    /**
     * A wall clock is a wall clock on every host, an instant an instant, a time with zone keeps
     * its zone. pgjdbc reads the JVM default zone per call (the slice-5 rule), so one test
     * asserts two hosts by flipping the default in between; before the seam the same row read
     * {@code 2026-01-15T22:30:00.123456Z} under UTC and {@code …T13:30:00.123456Z} under Tokyo.
     */
    @Test
    void aPostgresTemporalReadsTheSameUnderTwoJvmZones() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(first("/api/pg/ts")).as("timestamp under " + zone)
                        .isEqualTo("2026-01-15T22:30:00.123456");
                assertThat(first("/api/pg/tstz")).as("timestamptz under " + zone)
                        .isEqualTo("2026-01-15T22:30:00.123456Z");
                assertThat(first("/api/pg/d")).as("date under " + zone).isEqualTo("2026-01-15");
                assertThat(first("/api/pg/t")).as("time under " + zone).isEqualTo("22:30:00");
                assertThat(first("/api/pg/ttz")).as("timetz under " + zone)
                        .isEqualTo("22:30:00+09:00");
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /** Seconds always print: on the minute it is {@code T02:30:00}, not {@code T02:30}. */
    @Test
    void aWallClockOnTheMinutePrintsItsSeconds() throws Exception {
        assertThat(first("/api/pg/lit")).isEqualTo("2026-03-08T02:30:00");
    }

    /** A driver's wrapper is its text, and a SQL NULL is a JSON null. */
    @Test
    void aVendorWrapperIsItsTextAndNullIsNull() throws Exception {
        assertThat(first("/api/pg/jsonb")).isEqualTo("{\"sku\": \"A-1\"}");
        assertThat(first("/api/pg/interval")).startsWith("1 days 2 hours");
        assertThat(first("/api/pg/uuid")).isEqualTo("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
        JsonNode rows = rows("/api/pg/jsonb");
        assertThat(rows.get(1).get("v").isNull()).isTrue();
    }

    private static String first(String path) throws Exception {
        JsonNode value = rows(path).get(0).get("v");
        assertThat(value.isString()).as(path + " answers text, got " + value).isTrue();
        return value.asString();
    }

    private static JsonNode rows(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data");
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-temporal-json-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: temporal-json
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__probe.sql"), """
                create table probe (id integer primary key, ts timestamp, tstz timestamptz,
                    d date, t time, ttz timetz, u uuid, j jsonb, iv interval);
                insert into probe values (1, '2026-01-15 22:30:00.123456',
                    '2026-01-15 22:30:00.123456+00', '2026-01-15', '22:30:00', '22:30:00+09',
                    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '{"sku": "A-1"}', '1 day 02:00:00');
                insert into probe values (2, null, null, null, null, null, null, null, null);
                """);
        String[][] pg = {{"ts", "ts"}, {"tstz", "tstz"}, {"d", "d"}, {"t", "t"}, {"ttz", "ttz"},
                {"uuid", "u"}, {"jsonb", "j"}, {"interval", "iv"}};
        for (String[] column : pg) {
            route(home, "web/api/pg/" + column[0], "pg." + column[0], null,
                    "select id, " + column[1] + " as v from probe order by id\n;\n");
        }
        route(home, "web/api/pg/lit", "pg.lit", null,
                "select 1 as id, timestamp '2026-03-08 02:30:00' as v\n;\n");
        String[][] duck = {{"ts", "timestamp '2026-01-15 22:30:00.123456'"},
                {"tstz", "timestamptz '2026-01-15 22:30:00.123456+00'"},
                {"d", "date '2026-01-15'"}, {"t", "time '22:30:00.5'"}};
        for (String[] column : duck) {
            route(home, "web/api/duck/" + column[0], "duck." + column[0], "analytics",
                    "select 1 as id, " + column[1] + " as v\n;\n");
        }
        return home;
    }

    private static void route(Path home, String dir, String id, String datasource, String sql)
            throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                %ssecurity:
                  auth: public
                sources:
                  main:
                    sql:
                      file: probe.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(id, datasource == null ? "" : "datasource: " + datasource + "\n"));
        Files.writeString(route.resolve("probe.sql"), sql);
    }
}
