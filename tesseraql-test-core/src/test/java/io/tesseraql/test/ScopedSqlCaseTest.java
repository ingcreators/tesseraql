package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.PrincipalSpec;
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
 * A {@code sql} case with a {@code principal:} renders {@code /*%scope … *}{@code /}
 * directives through the production resolver (docs/data-scoping.md): matching arms bind the
 * principal's claims, no matching arm is deny-by-default, and a bypass role sees everything —
 * the suite-side proof the data-scope coverage kind promises.
 */
@Testcontainers
class ScopedSqlCaseTest {

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
            statement.execute("create table orders (id int primary key, region varchar(10))");
            statement.execute("insert into orders values (1, 'east'), (2, 'west')");
        }
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(appHome.resolve("scope"));
        Files.writeString(appHome.resolve("scope/orders_scope.yml"), """
                version: tesseraql/v1
                id: orders_scope
                kind: scope
                match:
                  - when: { role: admin }
                    apply: all
                  - when: { role: manager }
                    file: by_region.sql
                    params:
                      regions: principal.claim.regions
                """);
        Files.writeString(appHome.resolve("scope/by_region.sql"),
                "$.region in /* regions */ ('east')\n");
        Files.createDirectories(appHome.resolve("web"));
        Files.writeString(appHome.resolve("web/orders.sql"), """
                select o.id from orders o
                where /*%scope orders_scope on o */ (1=1)
                order by o.id
                """);
    }

    private static TestCase scoped(String name, PrincipalSpec principal, int expectedRows) {
        return new TestCase(name, new SqlTarget("web/orders.sql"), null, Map.of(),
                new Expectation(expectedRows, null, null), null, null, null, null, null,
                null, principal);
    }

    @Test
    void aMatchingArmBindsThePrincipalsClaims() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                scoped("manager sees their region",
                        new PrincipalSpec("u1", "u1", List.of("manager"), null, null,
                                Map.of("regions", List.of("east"))),
                        1))));
        assertThat(report.failed()).isZero();
    }

    @Test
    void aBypassRoleSeesEverything() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                scoped("admin sees all regions",
                        new PrincipalSpec("u2", "u2", List.of("admin"), null, null, Map.of()),
                        2))));
        assertThat(report.failed()).isZero();
    }

    @Test
    void noMatchingArmIsDenyByDefault() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                scoped("no arm, no rows",
                        new PrincipalSpec("u3", "u3", List.of("auditor"), null, null, Map.of()),
                        0))));
        assertThat(report.failed()).isZero();
    }

    @Test
    void aScopedFileWithoutAPrincipalStillRendersDenyByDefault() {
        TestReport report = new TestRunner(dataSource, appHome).run(new TestSuite(List.of(
                scoped("anonymous case sees nothing", null, 0))));
        assertThat(report.failed()).isZero();
    }
}
