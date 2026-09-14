package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.SqlTarget;
import io.tesseraql.test.TestSuite.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A suite's expectation compares against the text a route answers (docs/temporal-semantics.md
 * T2): a zoneless {@code timestamp} is {@code 2026-01-15T22:30:00}, an instant is at UTC. The
 * runner used to compare against the driver object's {@code toString()} —
 * {@code 2026-01-15 22:30:00.0} — so an author had to spell a JDBC class's own text, and a
 * different one per dialect, to pin a column the route served as ISO.
 */
@Testcontainers
class TemporalCaseTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static DataSource dataSource;

    @TempDir
    static Path appHome;

    @BeforeAll
    static void setUp() throws Exception {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(POSTGRES.getJdbcUrl());
        pg.setUser(POSTGRES.getUsername());
        pg.setPassword(POSTGRES.getPassword());
        dataSource = pg;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("create table events (id int primary key, at timestamp,"
                    + " tz timestamptz, on_day date)");
            statement.execute("insert into events values (1, '2026-01-15 22:30:00',"
                    + " '2026-01-15 22:30:00+09', '2026-01-15')");
        }
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(appHome.resolve("web"));
        Files.writeString(appHome.resolve("web/events.sql"),
                "select id, at, tz, on_day from events order by id\n");
    }

    @Test
    void aTemporalExpectationIsTheTextARouteAnswers() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                new TestCase("the wall clock, the instant and the date",
                        new SqlTarget("web/events.sql"),
                        null, Map.of(),
                        new Expectation(1, List.of(Map.of("at", "2026-01-15T22:30:00",
                                "tz", "2026-01-15T13:30:00Z", "on_day", "2026-01-15")), null),
                        null, null, null, null, null, null, null))));

        assertThat(report.failed()).as(report.toString()).isZero();
    }
}
