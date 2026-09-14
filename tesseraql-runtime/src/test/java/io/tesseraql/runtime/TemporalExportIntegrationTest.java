package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What a temporal column becomes in an export (docs/temporal-semantics.md T1): a wall clock is
 * printed as stored on every host, {@code timezone:} converts instants only, an untyped cell is
 * one SQL-style text per kind, and a spooled export carries a {@code uuid} and a {@code timetz}.
 * Before the read seam a zoneless {@code timestamp} reached the codec as a {@code Timestamp}
 * built in the JVM's zone: {@code type: datetime} + {@code timezone: Asia/Tokyo} rendered a
 * stored {@code 22:30} as {@code 07:30} on a UTC host and {@code 15:30} on Los Angeles, and the
 * DST-gap row moved by an hour with no declaration at all.
 */
@Testcontainers
class TemporalExportIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

    /** The headline: {@code timezone:} converts an instant and leaves a wall clock alone. */
    @Test
    void aTypedWallClockIsPrintedAsStoredAndATypedInstantInTheDeclaredZone() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "Asia/Tokyo", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                List<String> rows = csv("/api/export/typed");
                assertThat(rows.get(0)).isEqualTo("id,ts,tstz,d");
                assertThat(rows.get(1)).as("row 1 under " + zone)
                        .isEqualTo("1,2026-01-15 22:30:00,2026-01-16 07:30:00,2026-01-15");
                // The DST-gap wall clock (02:30 on the day Los Angeles springs forward).
                assertThat(rows.get(2)).as("the DST-gap row under " + zone)
                        .isEqualTo("2,2026-03-08 02:30:00,2026-03-08 11:30:00,2026-03-08");
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /**
     * A column typed through its domain alone writes the domain's format (docs/temporal-semantics.md
     * decision 25) — the value only the domain has; before, the reference was an unknown key and
     * the cell was the untyped SQL text.
     */
    @Test
    void aColumnTypedThroughItsDomainWritesTheDomainsFormat() throws Exception {
        List<String> rows = csv("/api/export/domain");
        assertThat(rows.get(0)).isEqualTo("id,d");
        assertThat(rows.get(1)).isEqualTo("1,2026/01/15");
    }

    /** An untyped cell is one SQL-style text per kind; an untyped instant takes the export zone. */
    @Test
    void anUntypedCellIsItsSqlText() throws Exception {
        List<String> rows = csv("/api/export/untyped");
        assertThat(rows.get(0)).isEqualTo("id,ts,tstz,d,t,ttz");
        assertThat(rows.get(1)).isEqualTo("1,2026-01-15 22:30:00.123456,2026-01-16 07:30:00.123456,"
                + "2026-01-15,22:30:00,22:30:00+09:00");
    }

    /** DuckDB used to print an untyped instant as ISO {@code T…Z}; now like every other dialect. */
    @Test
    void aDuckDbUntypedInstantPrintsLikeEveryOtherDialect() throws Exception {
        List<String> rows = csv("/api/export/duck");
        assertThat(rows.get(1)).isEqualTo("1,2026-01-15 22:30:00.123456,2026-01-16 07:30:00.123456,"
                + "2026-01-15,22:30:00.5");
    }

    /** A spooled export — here a split one — carries a {@code uuid} and a {@code timetz}. */
    @Test
    void aSpooledExportCarriesAUuidAndATimeWithZone() throws Exception {
        HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/export/split")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(200);
        List<String> entries = new ArrayList<>();
        String first = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                if (first == null) {
                    first = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(entries).containsExactly("keys-a.csv", "keys-b.csv");
        assertThat(first).contains("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
                .contains("22:30:00+09:00");
    }

    private static List<String> csv(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
        return List.of(response.body().strip().split("\r?\n"));
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-temporal-export-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: temporal-export
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
                    d date, t time, ttz timetz, u uuid, grp varchar(1));
                insert into probe values (1, '2026-01-15 22:30:00.123456',
                    '2026-01-15 22:30:00.123456+00', '2026-01-15', '22:30:00', '22:30:00+09',
                    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'a');
                insert into probe values (2, '2026-03-08 02:30:00', '2026-03-08 02:30:00+00',
                    '2026-03-08', '02:30:00', '02:30:00+00',
                    'b1ffcd00-1d1c-4f09-8c7e-7cc0ce491b22', 'b');
                """);
        route(home, "web/api/export/typed", "export.typed", null, """
                export:
                  format: csv
                  filename: typed.csv
                  timezone: Asia/Tokyo
                  columns:
                    - { name: id }
                    - { name: ts, type: datetime }
                    - { name: tstz, type: datetime }
                    - { name: d, type: date }
                """, "select id, ts, tstz, d from probe order by id\n;\n");
        Files.createDirectories(home.resolve("domains"));
        Files.writeString(home.resolve("domains/fields.yml"), """
                version: tesseraql/v1
                domains:
                  order_date:
                    type: date
                    format: yyyy/MM/dd
                """);
        route(home, "web/api/export/domain", "export.domain", null, """
                export:
                  format: csv
                  filename: domain.csv
                  columns:
                    - { name: id }
                    - { name: d, domain: order_date }
                """, "select id, d from probe where id = 1\n;\n");
        route(home, "web/api/export/untyped", "export.untyped", null, """
                export:
                  format: csv
                  filename: untyped.csv
                  timezone: Asia/Tokyo
                """, "select id, ts, tstz, d, t, ttz from probe where id = 1\n;\n");
        route(home, "web/api/export/duck", "export.duck", "analytics", """
                export:
                  format: csv
                  filename: duck.csv
                  timezone: Asia/Tokyo
                """, "select 1 as id, timestamp '2026-01-15 22:30:00.123456' as ts,"
                + " timestamptz '2026-01-15 22:30:00.123456+00' as tstz, date '2026-01-15' as d,"
                + " time '22:30:00.5' as t\n;\n");
        route(home, "web/api/export/split", "export.split", null, """
                export:
                  format: csv
                  filename: keys-{key}.csv
                  splitBy: grp
                """, "select grp, u, ttz from probe order by grp\n;\n");
        return home;
    }

    private static void route(Path home, String dir, String id, String datasource, String export,
            String sql) throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-export
                %ssecurity:
                  auth: public
                sources:
                  main:
                    sql:
                      file: probe.sql
                %s""".formatted(id, datasource == null ? "" : "datasource: " + datasource + "\n",
                export));
        Files.writeString(route.resolve("probe.sql"), sql);
    }
}
