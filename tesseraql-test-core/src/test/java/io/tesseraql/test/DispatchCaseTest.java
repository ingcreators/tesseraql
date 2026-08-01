package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.DispatchTarget;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.PrincipalSpec;
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
 * The {@code dispatch:} suite target (docs/transition-engine.md track C): a case runs the
 * one-action selector — the dispatch-level {@code decide:} once, then each member through
 * the documented transition pipeline, refusals rolling back to their savepoints and
 * falling through — inside the rolled-back transaction, so the button the UI actually
 * calls is assertable without HTTP.
 */
@Testcontainers
class DispatchCaseTest {

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
            statement.execute("create table docs (id varchar(20) primary key,"
                    + " amount numeric(10,2) not null, last_action varchar(20),"
                    + " status varchar(20))");
            statement.execute("insert into docs (id, amount, status) values"
                    + " ('D-1', 500, 'draft'), ('D-2', 0, 'draft'), ('D-4', -5, 'draft')");
        }
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: dispatch-case
                """);
        Files.createDirectories(appHome.resolve("decisions"));
        Files.writeString(appHome.resolve("decisions/lanes.yml"), """
                version: tesseraql/v1
                decisions:
                  lane:
                    inputs:
                      amount: { type: number, match: between }
                    outputs:
                      lane: { type: string, enum: [fast, slow] }
                    hitPolicy: first
                    rows:
                      - when: { amount: "> 100" }
                        out: { lane: slow }
                      - out: { lane: fast }
                """);
        Files.createDirectories(appHome.resolve("workflow"));
        Files.writeString(appHome.resolve("workflow/tick.sql"),
                "update docs set last_action = 'ticked' where id = /* key */ 'D-0'\n");
        Files.writeString(appHome.resolve("workflow/funded.sql"),
                "select 1 from docs where id = /* key */ 'D-0' and amount > 0\n");
        // The SQL-guarded/expression-guarded pair: clear needs funding, writeoff a zero.
        Files.writeString(appHome.resolve("workflow/settleable.yml"), """
                version: tesseraql/v1
                id: settleable
                kind: workflow
                mode: app
                document: { type: settleable, table: docs, key: id, stateColumn: status }
                http: { basePath: /api/settleable }
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: done, type: terminal }
                transitions:
                  - id: clear
                    from: draft
                    to: done
                    guard: { file: funded.sql, code: not-funded }
                    command: tick.sql
                  - id: writeoff
                    from: draft
                    to: done
                    guard: "document.amount == 0"
                    command: tick.sql
                dispatch:
                  - id: settle
                    oneOf: [clear, writeoff]
                """);
        // The dispatch-level decide: the members declare none and read decision.* from it.
        Files.writeString(appHome.resolve("workflow/routed.yml"), """
                version: tesseraql/v1
                id: routed
                kind: workflow
                mode: app
                document: { type: routed, table: docs, key: id, stateColumn: status }
                http: { basePath: /api/routed }
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: done, type: terminal }
                transitions:
                  - id: fastlane
                    from: draft
                    to: done
                    guard: "decision.route.lane == 'fast'"
                    command: tick.sql
                  - id: slowlane
                    from: draft
                    to: done
                    guard: "decision.route.lane == 'slow'"
                    command: tick.sql
                dispatch:
                  - id: route_next
                    decide:
                      route:
                        use: lane
                        params: { amount: document.amount }
                    oneOf: [fastlane, slowlane]
                """);
        Files.writeString(appHome.resolve("workflow/read-back.sql"),
                "select last_action from docs where id = /* key */ 'D-0'\n");
    }

    private static TestCase run(String name, String workflow, String key, String id,
            Map<String, Object> expectedRow, List<TestSuite.VerifyStep> verify) {
        return new TestCase(name, null, null, Map.of(),
                new Expectation(1, List.of(expectedRow), null), null, null, null, null, null,
                verify, new PrincipalSpec("u1", "aoki", List.of("writer"), null, null, Map.of()),
                null, new DispatchTarget(workflow, key, id));
    }

    @Test
    void theWinnerIsNamedAndItsPipelineRuns() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                run("a funded document settles through clear", "settleable", "D-1", "settle",
                        Map.of("transition", "clear", "from", "draft", "to", "done",
                                "dispatch", "settle"),
                        List.of(new TestSuite.VerifyStep(
                                new TestSuite.SqlTarget("workflow/read-back.sql"),
                                Map.of("key", "D-1"),
                                new Expectation(1,
                                        List.of(Map.of("last_action", "ticked")), null)))))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void aRefusedMemberFallsThroughToTheNext() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                run("an unfunded zero settles through writeoff", "settleable", "D-2", "settle",
                        Map.of("transition", "writeoff", "to", "done"), List.of()))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void noMemberHoldingNamesEveryAttempt() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                run("a negative amount fits no lane", "settleable", "D-4", "settle",
                        Map.of("code", "TQL-WORKFLOW-3202", "attempted", "clear,writeoff",
                                "dispatch", "settle"),
                        List.of()))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void givenStepsSeedTheMidFlowStateWithinTheRolledBackCase() {
        // D-1 starts at draft; the given step routes it to done through the dispatch's
        // member pipeline (real advance, real stamps), then the target dispatch finds
        // no member holding from the terminal state - the mid-flow refusal asserted
        // without committing anything.
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                new TestCase("a settled document fits no lane", null, null, Map.of(),
                        new Expectation(1, List.of(Map.of("code", "TQL-WORKFLOW-3202",
                                "attempted", "clear,writeoff")), null),
                        null, null, null, null, null, List.of(),
                        new PrincipalSpec("u1", "aoki", List.of("writer"), null, null,
                                Map.of()),
                        null, new DispatchTarget("settleable", "D-1", "settle"),
                        List.of(new TestSuite.GivenStep("settleable", "D-1", "clear",
                                null))))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void aRefusedGivenStepFailsTheCaseNamingTheStep() {
        // D-4 (-5) satisfies neither guard: the fixture itself cannot advance, and the
        // case fails naming the step instead of asserting a half-seeded state.
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                new TestCase("a fixture that cannot advance fails loudly", null, null,
                        Map.of(),
                        new Expectation(1, List.of(Map.of("to", "done")), null),
                        null, null, null, null, null, List.of(),
                        new PrincipalSpec("u1", "aoki", List.of("writer"), null, null,
                                Map.of()),
                        null, new DispatchTarget("settleable", "D-4", "settle"),
                        List.of(new TestSuite.GivenStep("settleable", "D-4", "clear",
                                null))))));
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.toString()).contains("given step 1")
                .contains("settleable.clear");
    }

    @Test
    void theDispatchLevelDecideRoutesTheDecideLessMembers() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                run("500 routes slow", "routed", "D-1", "route_next",
                        Map.of("transition", "slowlane", "to", "done"), List.of()),
                run("0 routes fast", "routed", "D-2", "route_next",
                        Map.of("transition", "fastlane", "to", "done"), List.of()))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }
}
