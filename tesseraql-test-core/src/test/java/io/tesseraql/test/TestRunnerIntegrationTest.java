package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the declarative test runner (design ch. 13): SQL-file and Identity SQL
 * Contract cases are executed and asserted, and a failing expectation is reported as a failure.
 */
@Testcontainers
class TestRunnerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TestRunner runner;
    static DataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = dataSource();
        seed(dataSource);
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        IdentityService identity = new IdentityService(name -> dataSource);
        runner = new TestRunner(dataSource, appHome, identity,
                RealmConfig.managed("local", "main"));
    }

    @Test
    void runsSqlAndContractCasesAndReportsFailures() {
        TestSuite suite = new TestSuiteLoader().parse("""
                tests:
                  - name: search finds sato
                    sql:
                      file: web/api/users/search.sql
                    params:
                      q: sato
                      limit: 50
                      offset: 0
                    expect:
                      rowCount: 1
                      rows:
                        - name: sato
                  - name: roles for u1
                    contract: identity.find-roles-by-user-id
                    params:
                      userId: u1
                    expect:
                      rows:
                        - role_code: USER_READ
                  - name: deliberately failing
                    sql:
                      file: web/api/users/search.sql
                    params:
                      q: nobody
                      limit: 50
                      offset: 0
                    expect:
                      rowCount: 1
                """);

        TestReport report = runner.run(suite);

        assertThat(report.results()).hasSize(3);
        assertThat(report.passed()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.results().get(0).passed()).isTrue();
        assertThat(report.results().get(1).passed()).isTrue();
        assertThat(report.results().get(2).passed()).isFalse();
        assertThat(report.results().get(2).message()).contains("rowCount");
    }

    /**
     * A write file is a first-class sql case: it runs inside an always-rolled-back transaction,
     * {@code expect.updateCount} asserts the affected rows, and a {@code verify:} read-back on
     * the same connection observes the uncommitted write — while the database is left untouched
     * once the case ends.
     */
    @Test
    void runsWriteCasesInARolledBackTransaction() throws Exception {
        TestSuite suite = new TestSuiteLoader().parse("""
                tests:
                  - name: deactivating sato affects one row and the read-back sees it
                    sql:
                      file: web/api/users/deactivate/deactivate.sql
                    params:
                      name: sato
                    expect:
                      updateCount: 1
                    verify:
                      - sql:
                          file: web/api/users/search.sql
                        params:
                          q: sato
                          limit: 50
                          offset: 0
                        expect:
                          rowCount: 1
                          rows:
                            - status: INACTIVE
                  - name: deactivating an unknown user affects nothing
                    sql:
                      file: web/api/users/deactivate/deactivate.sql
                    params:
                      name: nobody
                    expect:
                      updateCount: 0
                """);

        TestReport report = runner.run(suite);

        assertThat(report.results()).allMatch(TestReport.TestResult::passed,
                report.results().toString());
        // The rollback guarantee: the write never persisted, so the seeded row is untouched.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement
                        .executeQuery("select status from users where name = 'sato'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("ACTIVE");
        }
    }

    /** A write with a RETURNING clause produces result rows and is asserted like a query. */
    @Test
    void aReturningWriteYieldsResultRows(@org.junit.jupiter.api.io.TempDir Path appHome)
            throws Exception {
        java.nio.file.Files.writeString(appHome.resolve("insert-returning.sql"), """
                insert into users (name, status)
                values (/* name */'demo', 'PENDING')
                returning name, status
                """);
        TestRunner writeRunner = new TestRunner(dataSource, appHome);
        TestSuite suite = new TestSuiteLoader().parse("""
                tests:
                  - name: the insert returns the new row
                    sql:
                      file: insert-returning.sql
                    params:
                      name: tanaka
                    expect:
                      rowCount: 1
                      rows:
                        - name: tanaka
                          status: PENDING
                """);

        TestReport report = writeRunner.run(suite);

        assertThat(report.allPassed()).as(report.results().toString()).isTrue();
        // The RETURNING insert rolled back with its transaction.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var resultSet = statement
                        .executeQuery("select count(*) from users where name = 'tanaka'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isZero();
        }
    }

    /** Misusing the assertion kinds fails the case with a message naming the right one. */
    @Test
    void mismatchedExpectationsFailWithGuidance() {
        TestSuite suite = new TestSuiteLoader().parse("""
                tests:
                  - name: rowCount asserted on a write
                    sql:
                      file: web/api/users/deactivate/deactivate.sql
                    params:
                      name: sato
                    expect:
                      rowCount: 1
                  - name: updateCount asserted on a query
                    sql:
                      file: web/api/users/search.sql
                    params:
                      q: sato
                      limit: 50
                      offset: 0
                    expect:
                      updateCount: 1
                  - name: wrong updateCount
                    sql:
                      file: web/api/users/deactivate/deactivate.sql
                    params:
                      name: nobody
                    expect:
                      updateCount: 1
                  - name: verify without a sql target
                    contract: identity.find-roles-by-user-id
                    params:
                      userId: u1
                    verify:
                      - sql:
                          file: web/api/users/search.sql
                  - name: a write as a verify step
                    sql:
                      file: web/api/users/deactivate/deactivate.sql
                    params:
                      name: sato
                    verify:
                      - sql:
                          file: web/api/users/deactivate/deactivate.sql
                        params:
                          name: sato
                """);

        TestReport report = runner.run(suite);

        assertThat(report.results()).hasSize(5);
        assertThat(report.results()).allMatch(result -> !result.passed(),
                report.results().toString());
        assertThat(report.results().get(0).message())
                .contains("assert expect.updateCount, not rowCount/rows");
        assertThat(report.results().get(1).message()).contains("assert rowCount/rows");
        assertThat(report.results().get(2).message())
                .contains("expected updateCount 1 but was 0");
        assertThat(report.results().get(3).message()).contains("require a sql target");
        assertThat(report.results().get(4).message())
                .contains("verify steps are read-backs");
    }

    /**
     * Phase 19: a validation case evaluates a route's {@code validate:} block — SQL rules
     * against the test database, expression rules against the case's params — and asserts on
     * the violations as rows, so a rule is testable without serving the route.
     */
    @Test
    void evaluatesRouteValidationRulesAndReturnsViolationsAsRows(
            @org.junit.jupiter.api.io.TempDir Path appHome) throws Exception {
        writeValidatedApp(appHome);
        TestRunner validationRunner = new TestRunner(dataSource, appHome);
        TestSuite suite = new TestSuiteLoader().parse("""
                tests:
                  - name: a taken name is rejected with the declared field error
                    validate:
                      route: members.register
                    params:
                      body:
                        name: sato
                        startDate: "2026-01-01"
                        endDate: "2026-12-31"
                    expect:
                      rowCount: 1
                      rows:
                        - rule: uniqueName
                          field: name
                          code: duplicate
                          message: members.name.duplicate
                  - name: end before start violates the cross-field rule
                    validate:
                      route: members.register
                      rule: dateOrder
                    params:
                      body:
                        name: brand-new
                        startDate: "2026-12-31"
                        endDate: "2026-01-01"
                    expect:
                      rowCount: 1
                      rows:
                        - rule: dateOrder
                          field: endDate
                          code: end-before-start
                  - name: a fresh name with ordered dates passes every rule
                    validate:
                      route: members.register
                    params:
                      body:
                        name: brand-new
                        startDate: "2026-01-01"
                        endDate: "2026-12-31"
                    expect:
                      rowCount: 0
                """);

        TestReport report = validationRunner.run(suite);

        assertThat(report.results()).hasSize(3);
        assertThat(report.results()).allMatch(TestReport.TestResult::passed,
                report.results().toString());
    }

    /** A minimal app declaring a SQL uniqueness rule and a cross-field expression rule. */
    private static void writeValidatedApp(Path appHome) throws Exception {
        java.nio.file.Files.createDirectories(appHome.resolve("config"));
        java.nio.file.Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: members\n");
        Path routeDir = appHome.resolve("web/members");
        java.nio.file.Files.createDirectories(routeDir);
        java.nio.file.Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                    required: true
                validate:
                  uniqueName:
                    file: check-name.sql
                    params:
                      name: body.name
                    field: name
                    code: duplicate
                    message: members.name.duplicate
                  dateOrder:
                    when: body.endDate != null
                    rule: body.endDate >= body.startDate
                    field: endDate
                    code: end-before-start
                sql:
                  file: insert-member.sql
                  mode: update
                  params:
                    name: body.name
                response:
                  json:
                    status: 201
                    body:
                      affected: sql.affectedRows
                """);
        java.nio.file.Files.writeString(routeDir.resolve("check-name.sql"), """
                select
                  'name' as field
                from
                  users
                where
                  name = /* name */'sato'
                """);
        java.nio.file.Files.writeString(routeDir.resolve("insert-member.sql"), """
                insert into users (name, status) values (/* name */'sato', 'ACTIVE')
                """);
    }

    private static void seed(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32), created_at timestamp default now())");
            statement.execute("insert into users (name, status) values ('sato','ACTIVE')");
            statement.execute("create table tql_users (user_id varchar(64) primary key, "
                    + "login_id varchar(200), display_name varchar(200), email varchar(320), "
                    + "status varchar(32), tenant_id varchar(64), version bigint default 0)");
            statement.execute("create table tql_roles (role_id varchar(64) primary key, "
                    + "role_code varchar(200), role_name varchar(200))");
            statement.execute(
                    "create table tql_user_roles (user_id varchar(64), role_id varchar(64))");
            statement.execute(
                    "create table tql_user_groups (user_id varchar(64), group_id varchar(64))");
            statement.execute(
                    "create table tql_group_roles (group_id varchar(64), role_id varchar(64))");
            statement.execute("insert into tql_users (user_id, login_id, display_name, status) "
                    + "values ('u1','admin','Admin','ACTIVE')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name) "
                    + "values ('r1','USER_READ','Reader')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
