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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the declarative test runner (design ch. 13): SQL-file and Identity SQL
 * Contract cases are executed and asserted, and a failing expectation is reported as a failure.
 */
@Testcontainers
class TestRunnerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
