package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.LockTarget;
import io.tesseraql.test.TestSuite.SqlTarget;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.test.TestSuite.ValidateTarget;
import io.tesseraql.test.TestSuite.VerifyStep;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A {@code sql} case with a {@code lock:} renders {@code /*%lock*}{@code /} through the column a
 * route declares (docs/edit-conflict.md): a current lock writes, a stale one writes nothing, and
 * {@code overwrite:} waives the comparison. Before this key a locked statement could not be a
 * suite target at all — only the command pipeline built the {@code LockBinding} the directive
 * needs, so every scaffolded app's update and delete left {@code tesseraql test}.
 */
@Testcontainers
class LockedSqlCaseTest {

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
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(appHome.resolve("web/docs"));
        Files.writeString(appHome.resolve("web/docs/post.yml"), """
                version: tesseraql/v1
                id: docs.update
                kind: route
                recipe: command-json
                lock: { column: version, type: integer }

                input:
                  id:
                    type: integer
                    required: true
                  title:
                    type: string
                    required: true

                steps:
                  - id: main
                    sql:
                      file: update.sql
                      mode: update
                      params:
                        id: params.id
                        title: params.title
                """);
        // The unlocked twin: a route that declares no lock: names no column, so a lock case
        // pointed at it has nothing to seed. It needs its own directory because only an
        // HTTP-method file name is a route document.
        Files.createDirectories(appHome.resolve("web/docs/touch"));
        Files.writeString(appHome.resolve("web/docs/touch/post.yml"), """
                version: tesseraql/v1
                id: docs.touch
                kind: route
                recipe: command-json

                input:
                  id:
                    type: integer
                    required: true

                steps:
                  - id: main
                    sql:
                      file: touch.sql
                      mode: update
                      params:
                        id: params.id
                """);
        Files.writeString(appHome.resolve("web/docs/touch/touch.sql"),
                "update docs set title = title where id = /* id */ 1\n");
        // audit.user rides the case's own params: — an ordinary bind a suite has always been
        // able to write, unlike the lock, which no YAML scalar can stand in for.
        Files.writeString(appHome.resolve("web/docs/update.sql"), """
                update docs
                set title = /* title */ 'x',
                    version = version + 1,
                    updated_by = /* audit.user */ 'someone'
                where id = /* id */ 1
                  and /*%lock*/ (1=1)
                """);
        Files.writeString(appHome.resolve("web/docs/select.sql"), """
                select title, version, updated_by from docs where id = /* id */ 1
                """);
    }

    @BeforeEach
    void seed() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("drop table if exists docs");
            statement.execute("create table docs (id int primary key, title varchar(50),"
                    + " version int not null, updated_by varchar(50) not null)");
            statement.execute("insert into docs values (1, 'first', 7, 'seed')");
        }
    }

    private static TestCase locked(String name, LockTarget lock, Integer updateCount,
            List<VerifyStep> verify) {
        return new TestCase(name, new SqlTarget("web/docs/update.sql"), null,
                Map.of("id", 1, "title", "edited", "audit", Map.of("user", "suite")),
                new Expectation(null, null, updateCount), null, null, null, null, null,
                verify, null, null, null, null, lock);
    }

    @Test
    void aCurrentLockWritesTheRow() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("the current lock writes", new LockTarget("docs.update", 7, null), 1,
                        List.of(new VerifyStep(new SqlTarget("web/docs/select.sql"),
                                Map.of("id", 1),
                                new Expectation(1, List.of(Map.of("title", "edited",
                                        "version", 8, "updated_by", "suite")), null)))))));

        assertThat(report.failed()).as(() -> report.results().toString()).isZero();
    }

    @Test
    void aStaleLockWritesNothing() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("a stale lock writes nothing", new LockTarget("docs.update", 6, null), 0,
                        List.of()))));

        assertThat(report.failed()).as(() -> report.results().toString()).isZero();
    }

    @Test
    void anOverwriteWaivesTheComparison() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("overwrite writes over a stale value",
                        new LockTarget("docs.update", 6, true), 1, List.of()))));

        assertThat(report.failed()).as(() -> report.results().toString()).isZero();
    }

    @Test
    void aCaseWithNoLockStillRefusesTheDirective() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("no lock declared", null, 1, List.of()))));

        // The refusal is the point: an unarmed locked write must not quietly render (1=1) and
        // pass. Seeding a lock is opting in, never a default the runner supplies.
        assertThat(report.results().get(0).message()).contains("TQL-SQL-2115");
    }

    @Test
    void aLockOnARouteThatDeclaresNoneIsRefused() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("no column to seed", new LockTarget("docs.touch", 7, null), 1,
                        List.of()))));

        assertThat(report.results().get(0).message())
                .contains("docs.touch").contains("declares no lock:");
    }

    @Test
    void aLockOnAnUnknownRouteIsRefused() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("unknown route", new LockTarget("docs.nope", 7, null), 1, List.of()))));

        assertThat(report.results().get(0).message())
                .contains("Unknown route 'docs.nope'").contains("lock.route");
    }

    @Test
    void aLockWithNeitherValueNorOverwriteIsRefused() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                locked("nothing to compare", new LockTarget("docs.update", null, null), 1,
                        List.of()))));

        assertThat(report.results().get(0).message()).contains("needs a value:");
    }

    @Test
    void aLockOnACaseWithNoSqlTargetIsRefused() {
        TestCase validating = new TestCase("a validate case with a lock", null, null, Map.of(),
                null, new ValidateTarget("docs.update", null), null, null, null, null, null, null,
                null, null, null, new LockTarget("docs.update", 7, null));

        TestReport report = new TestRunner(dataSource, appHome)
                .run(new TestSuite(List.of(validating)));

        assertThat(report.results().get(0).message()).contains("requires a sql target");
    }
}
