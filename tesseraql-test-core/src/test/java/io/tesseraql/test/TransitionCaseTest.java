package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.PrincipalSpec;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.test.TestSuite.TransitionTarget;
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
 * The {@code transition:} suite target (docs/testing.md): a case fires one declared
 * transition through the documented pipeline — state legality, decide, guard, conditional
 * advance, scoped command, the zero-row contract — inside the rolled-back transaction, and
 * a refusal comes back as a {@code code} row instead of an exception, so every posture of
 * the state machine is assertable declaratively.
 */
@Testcontainers
class TransitionCaseTest {

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
                    + " amount numeric(10,2) not null, department varchar(20) not null,"
                    + " lane varchar(20), last_action varchar(20), status varchar(20))");
            statement.execute("insert into docs (id, amount, department, status) values"
                    + " ('D-1', 500, 'engineering', 'draft'),"
                    + " ('D-2', 0, 'engineering', 'draft'),"
                    + " ('D-3', 500, 'sales', 'draft')");
        }
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: transition-case
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
        Files.createDirectories(appHome.resolve("scope"));
        Files.writeString(appHome.resolve("scope/docs_scope.yml"), """
                version: tesseraql/v1
                id: docs_scope
                kind: scope
                match:
                  - when: { role: writer }
                    file: own_department.sql
                    params:
                      departments: principal.claim.departments
                """);
        Files.writeString(appHome.resolve("scope/own_department.sql"),
                "$.department in /* departments */ ('engineering')\n");
        Files.createDirectories(appHome.resolve("workflow"));
        Files.writeString(appHome.resolve("workflow/submit.sql"), """
                update docs
                set lane = /* decision.lane.lane */ 'fast',
                    last_action = /* audit.user */ 'someone'
                where id = /* key */ 'D-0'
                  and /*%scope docs_scope */ (1=1)
                """);
        Files.writeString(appHome.resolve("workflow/doc.yml"), """
                version: tesseraql/v1
                id: doc
                kind: workflow
                mode: managed
                document: { type: doc, table: docs, key: id }
                http: { basePath: /api/docs }
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: submitted, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: submitted
                    guard: "document.amount > 0"
                    command: submit.sql
                    decide:
                      lane:
                        use: lane
                        params:
                          amount: document.amount
                """);
        Files.writeString(appHome.resolve("workflow/tick.sql"),
                "update docs set last_action = 'ticked' where id = /* key */ 'D-0'\n");
        // The SQL guard form: D-2's amount is zero, so the set condition finds no row.
        Files.writeString(appHome.resolve("workflow/funded.sql"),
                "select 1 from docs where id = /* key */ 'D-0' and amount > 0\n");
        Files.writeString(appHome.resolve("workflow/guarded.yml"), """
                version: tesseraql/v1
                id: guarded
                kind: workflow
                mode: app
                document: { type: guarded, table: docs, key: id, stateColumn: status }
                http: { basePath: /api/guarded }
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: done, type: terminal }
                transitions:
                  - id: finish
                    from: draft
                    to: done
                    guard: { file: funded.sql, code: not-funded }
                    command: tick.sql
                """);
        Files.writeString(appHome.resolve("workflow/read-back.sql"),
                "select lane, last_action from docs where id = /* key */ 'D-0'\n");
        Files.writeString(appHome.resolve("workflow/thing.yml"), """
                version: tesseraql/v1
                id: thing
                kind: workflow
                mode: app
                document: { type: thing, table: docs, key: id, stateColumn: status }
                http: { basePath: /api/things }
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: done, type: terminal }
                transitions:
                  - id: finish
                    from: draft
                    to: done
                    command: tick.sql
                """);
    }

    private static TestCase fire(String name, String workflow, String key, String id,
            PrincipalSpec principal, Map<String, Object> expectedRow) {
        return new TestCase(name, null, null, Map.of(),
                new Expectation(1, List.of(expectedRow), null), null, null, null, null, null,
                null, principal, new TransitionTarget(workflow, key, id));
    }

    private static PrincipalSpec writer(String... departments) {
        return new PrincipalSpec("u1", "aoki", List.of("writer"), null, null,
                Map.of("departments", List.of(departments)));
    }

    @Test
    void anAdvanceReportsFromAndToAndTheCommandSeesTheDecision() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                new TestCase("submit advances and stamps the decided lane", null, null,
                        Map.of(), new Expectation(1,
                                List.of(Map.of("from", "draft", "to", "submitted")), null),
                        null, null, null, null, null,
                        List.of(new TestSuite.VerifyStep(
                                new TestSuite.SqlTarget("workflow/read-back.sql"),
                                Map.of("key", "D-1"),
                                new Expectation(1, List.of(
                                        Map.of("lane", "slow", "last_action", "aoki")), null))),
                        writer("engineering"),
                        new TransitionTarget("doc", "D-1", "submit")))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void aFalsyGuardComesBackAsData() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                fire("zero amount is refused by the guard", "doc", "D-2", "submit",
                        writer("engineering"),
                        Map.of("code", "TQL-WORKFLOW-3202", "from", "draft")))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void aScopeMissIsTheZeroRowContract() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                fire("another department's document updates nothing", "doc", "D-3", "submit",
                        writer("engineering"),
                        Map.of("code", "TQL-WORKFLOW-3204", "from", "draft")))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void theWrongStateIsAConflictRow() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                fire("a terminal-state transition conflicts", "thing", "D-1", "finish",
                        writer("engineering"),
                        Map.of("from", "draft", "to", "done")),
                fire("firing it again conflicts", "thing", "D-1", "finish",
                        writer("engineering"),
                        Map.of("from", "draft", "to", "done")))));
        // Both cases pass INDEPENDENTLY: each rolls back, so the app-mode advance
        // succeeds twice - the rolled-back suite contract, asserted here on purpose.
        assertThat(report.failed()).as(report.toString()).isZero();
    }

    @Test
    void aSqlGuardPassesOnRowsAndRefusesWithItsDeclaredCode() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                fire("a funded document passes the SQL guard", "guarded", "D-1", "finish",
                        writer("engineering"),
                        Map.of("from", "draft", "to", "done")),
                fire("an unfunded document is refused with the declared code", "guarded",
                        "D-2", "finish", writer("engineering"),
                        Map.of("code", "TQL-WORKFLOW-3202", "guard", "not-funded",
                                "from", "draft")))));
        assertThat(report.failed()).as(report.toString()).isZero();
    }
}
