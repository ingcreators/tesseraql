package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.test.TestReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the test goal logic (design ch. 18): runs the example app's declarative
 * suites against PostgreSQL and writes the reports.
 */
@Testcontainers
class AppTestRunnerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static DataSource dataSource;

    @BeforeAll
    static void seed() throws Exception {
        dataSource = dataSource();
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

    @Test
    void runsExampleSuitesWritesReportsAndCollectsCoverage(@TempDir Path reportDir) {
        Path appHome = Path.of("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        AppTestRunner.RunResult result = new AppTestRunner()
                .run(appHome, dataSource, RealmConfig.managed("local", "main"), reportDir);

        TestReport report = result.report();
        assertThat(report.results()).hasSize(12);
        assertThat(report.allPassed()).isTrue();
        assertThat(Files.exists(reportDir.resolve("junit/TEST-tesseraql.xml"))).isTrue();
        assertThat(Files.exists(reportDir.resolve("tesseraql-result.json"))).isTrue();
        assertThat(Files.exists(reportDir.resolve("index.html"))).isTrue();
        assertThat(Files.exists(reportDir.resolve("coverage/sql-coverage.json"))).isTrue();
        assertThat(Files.exists(reportDir.resolve("coverage/coverage.sarif"))).isTrue();
        assertThat(Files.exists(reportDir.resolve("coverage/cobertura.xml"))).isTrue();
        assertThat(Files.exists(reportDir.resolve("coverage/sonarqube.xml"))).isTrue();
        try (var allureFiles = Files.list(reportDir.resolve("allure-results"))) {
            assertThat(allureFiles.filter(f -> f.toString().endsWith("-result.json")).count())
                    .isEqualTo(12);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }

        // Both branches of search.sql are exercised (q present and empty) -> 100% branch coverage.
        assertThat(result.coverage().report("web/api/users/search.sql").branchRatio())
                .isEqualTo(1.0);
        // The derived coverage kinds are available on the result.
        assertThat(result.kind("assertion").ratio()).isBetween(0.0, 1.0);
        assertThat(result.kind("iam-contract").kind()).isEqualTo("iam-contract");
        // The manifest-based kinds: the users route binds search.sql, which the suite exercises.
        assertThat(result.kind("route").covered()).contains("users.search");
        assertThat(result.kind("security").declared()).isNotEmpty();
        // No SAML linking and no SCIM in the example app: nothing declared, ratio 1.0.
        assertThat(result.kind("saml").ratio()).isEqualTo(1.0);
        assertThat(result.kind("scim").ratio()).isEqualTo(1.0);
        // The provision route's userExists rule is declared and the suite evaluates it.
        assertThat(result.kind("validation").declared())
                .containsExactly("users.apiProvision.userExists");
        assertThat(result.kind("validation").covered())
                .containsExactly("users.apiProvision.userExists");
        assertThat(result.kind("validation").ratio()).isEqualTo(1.0);
        // Phase 20: the provision route's notifications and the maintenance job's notify step
        // are declared, and the suite's notify cases evaluate all of them.
        assertThat(result.kind("notification").declared()).containsExactlyInAnyOrder(
                "users.apiProvision.confirmation", "users.apiProvision.audit",
                "user.dailyMaintenance.report");
        assertThat(result.kind("notification").ratio()).isEqualTo(1.0);
        // Phase 26: the directory sync job's http-call step is declared, and the suite's
        // http-call case plans it (resolving url, query, and the allow-list, no network call).
        assertThat(result.kind("http-call").declared()).containsExactly("directory.sync.headcount");
        assertThat(result.kind("http-call").covered()).containsExactly("directory.sync.headcount");
        assertThat(result.kind("http-call").ratio()).isEqualTo(1.0);
        // Phase 21: the print route exports a pdf document; its extraction SQL is exercised.
        assertThat(result.kind("document").declared()).containsExactly("users.print");
        assertThat(result.kind("document").covered()).containsExactly("users.print");
        assertThat(result.kind("document").ratio()).isEqualTo(1.0);
        // Phase 22: both shipped catalogs are declared and the messages cases read them.
        assertThat(result.kind("message").declared()).containsExactlyInAnyOrder("en", "ja");
        assertThat(result.kind("message").covered()).containsExactlyInAnyOrder("en", "ja");
        assertThat(result.kind("message").ratio()).isEqualTo(1.0);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
