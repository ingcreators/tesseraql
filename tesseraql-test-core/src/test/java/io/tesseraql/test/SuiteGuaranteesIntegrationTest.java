package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.expr.ExpressionFunction;
import io.tesseraql.core.expr.ExpressionFunctions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two guarantees the runner states and did not keep (docs/audit-low-leads.md slice 12): a test
 * run never commits anything (G17 — a {@code commit;} inside the case's file ended the transaction
 * the runner thought it owned), and a case compiles against the registry the runner was handed
 * (G20 — the expression-evaluating kinds read the process default, so a module function passed
 * under {@code tesseraql test} and failed as unknown under the MCP test tool and Studio).
 */
@Testcontainers
class SuiteGuaranteesIntegrationTest {

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
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table items (id serial primary key, name varchar(100),"
                    + " qty integer not null)");
            statement.execute("insert into items (name, qty) values ('First item', 1)");
        }
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(appHome.resolve("web/items"));
        Files.writeString(appHome.resolve("web/items/count.sql"),
                "select count(*) as n from items\n");
        Files.writeString(appHome.resolve("web/items/leak.sql"),
                "insert into items (name, qty) values ('leaked', 1);\ncommit;\n");
        Files.writeString(appHome.resolve("web/items/insert.sql"),
                "insert into items (name, qty) values (/* body.name */'x', /* body.qty */1)\n");
        Files.writeString(appHome.resolve("web/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                    required: true
                  qty:
                    type: integer
                    required: true
                validate:
                  smallOrder:
                    rule: twice(body.qty) <= 10
                    field: qty
                    code: too-large
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                response:
                  json:
                    status: 201
                    body:
                      affected: steps.main.affectedRows
                """);
    }

    @Test
    void aCaseWhoseFileCommitsIsRefusedAndLeaksNothing() throws Exception {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuiteLoader().parse("""
                version: tesseraql/v1
                tests:
                  - name: count before
                    sql:
                      file: web/items/count.sql
                    expect:
                      rows:
                        - n: 1
                  - name: the file that commits
                    sql:
                      file: web/items/leak.sql
                  - name: count after
                    sql:
                      file: web/items/count.sql
                    expect:
                      rows:
                        - n: 1
                """));

        // The offending case fails with the code that names the statement and its line; the
        // count cases around it pass, and the table is what it was — the row used to persist
        // and every later run added one more.
        assertThat(report.results()).extracting(TestReport.TestResult::name,
                TestReport.TestResult::passed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("count before", true),
                        org.assertj.core.groups.Tuple.tuple("the file that commits", false),
                        org.assertj.core.groups.Tuple.tuple("count after", true));
        assertThat(report.results().get(1).message()).contains("TQL-SQL-2123")
                .contains("line 2 (commit)");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from items")) {
            rows.next();
            assertThat(rows.getLong(1)).as("nothing leaked").isEqualTo(1);
        }
    }

    @Test
    void anExpressionCaseCompilesAgainstTheRegistryTheRunnerWasHanded() {
        // The MCP test tool's shape: a registry handed in, no process default installed. The
        // validate kind used to compile its rule through the no-registry overload, so twice()
        // was "unknown" here and known under tesseraql test.
        ExpressionFunctions registry = ExpressionFunctions.of(List.of(new ExpressionFunction() {
            @Override
            public String name() {
                return "twice";
            }

            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object apply(List<Object> args) {
                return args.get(0) == null
                        ? null
                        : new java.math.BigDecimal(String.valueOf(args.get(0)))
                                .multiply(java.math.BigDecimal.TWO);
            }
        }));
        TestRunner runner = new TestRunner(dataSource, appHome, null, null, null, registry);

        TestReport report = runner.run(new TestSuiteLoader().parse("""
                version: tesseraql/v1
                tests:
                  - name: a large quantity violates the module-function rule
                    validate:
                      route: items.create
                    params:
                      body:
                        name: bulk
                        qty: 6
                    expect:
                      rowCount: 1
                      rows:
                        - rule: smallOrder
                          field: qty
                          code: too-large
                  - name: a small quantity passes it
                    validate:
                      route: items.create
                    params:
                      body:
                        name: one
                        qty: 2
                    expect:
                      rowCount: 0
                """));

        assertThat(report.results()).allMatch(TestReport.TestResult::passed,
                report.results().toString());
    }
}
