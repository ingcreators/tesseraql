package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.docs.ReportDoc;
import io.tesseraql.report.docs.ReportDoc.CaseResult;
import io.tesseraql.report.docs.ReportGenerator;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
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
 * Integration test for the report overlay (documentation portal v2): runs the example app's
 * declarative suites against PostgreSQL and asserts {@link ReportGenerator} joins the real run
 * results and SQL line/branch coverage onto the routes, keeping the covered/coverable line numbers.
 */
@Testcontainers
class ReportGeneratorIntegrationTest {

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
            statement.execute("create table app_groups (id serial primary key, "
                    + "display_name varchar(200) not null)");
            statement.execute("insert into app_groups (display_name) values ('engineers')");
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
    void overlaysRunResultsAndSqlLineSetsOntoRoutes(@TempDir Path reportDir) {
        Path appHome = Path.of("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        AppTestRunner.RunResult result = new AppTestRunner()
                .run(appHome, dataSource, RealmConfig.managed("local", "main"), reportDir);
        AppManifest manifest = new ManifestLoader().load(appHome);

        ReportGenerator generator = new ReportGenerator();
        ReportDoc doc = generator.generate(manifest, result, new CoverageThresholds(0.0, 0.0),
                "it", "2026-06-15T12:00:00Z");

        // Run-level summary mirrors the suite (17 cases, all passing) and the gate passes at 0%.
        assertThat(doc.summary().total()).isEqualTo(17);
        assertThat(doc.summary().passed()).isEqualTo(17);
        assertThat(doc.summary().failed()).isZero();
        assertThat(doc.summary().sqlLineRatio()).isBetween(0.0, 1.0);
        assertThat(doc.gate().passed()).isTrue();
        assertThat(doc.summary().gatePassed()).isTrue();

        ReportDoc.RouteReport search = doc.routes().get("users.search");
        assertThat(search).isNotNull();
        assertThat(search.covered()).isTrue();
        // The covering SQL test cases are joined to their pass/fail results by name.
        assertThat(search.tests()).extracting(CaseResult::name, CaseResult::passed)
                .contains(tuple("search finds sato by name", true),
                        tuple("search without query returns all users", true));
        // SQL coverage keeps the actual covered/coverable line numbers for highlighting; both
        // branches of search.sql are exercised so its branch ratio is 100%.
        assertThat(search.sql()).anySatisfy(sql -> {
            assertThat(sql.file()).isEqualTo("web/api/users/search.sql");
            assertThat(sql.branchRatio()).isEqualTo(1.0);
            assertThat(sql.coverableLines()).isNotEmpty();
            assertThat(sql.coveredLines()).isNotEmpty();
        });

        // The whole overlay serialises to JSON carrying the line-set keys the spec layer lacks.
        assertThat(generator.toJson(doc))
                .contains("web/api/users/search.sql").contains("\"coveredLines\"");
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
