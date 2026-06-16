package io.tesseraql.coverage.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/**
 * Live Query Plan Guard test on SQL Server (design ch. 42, 46): real {@code SHOWPLAN_XML}
 * documents normalize so the same guard rules apply across dialects. Gated behind
 * {@code -Dtesseraql.dialect.its=true} because the SQL Server image is large; the normalization
 * itself stays covered by the always-on unit tests.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tesseraql.dialect.its", matches = "true")
class SqlServerPlanGuardIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final MSSQLServerContainer SQLSERVER = new MSSQLServerContainer(
            "mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    private static final SqlServerPlanInspector INSPECTOR = new SqlServerPlanInspector();

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table t (id int identity primary key, name varchar(64), val int)");
            StringBuilder insert = new StringBuilder("insert into t (name, val) values ");
            for (int i = 1; i <= 500; i++) {
                insert.append(i > 1 ? "," : "").append("('n").append(i).append("', ").append(i)
                        .append(")");
            }
            statement.execute(insert.toString());
            statement.execute("update statistics t");
        }
    }

    @Test
    void primaryKeyLookupIsNotASeqScan() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection,
                    "select * from t where id = ?", List.of(250));
            assertThat(plan.flatten()).noneMatch(node -> "Seq Scan".equals(node.nodeType()));
            assertThat(PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000))).isEmpty();
        }
    }

    @Test
    void fullScanIsRejected() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection,
                    "select * from t where name = ?", List.of("n1"));
            assertThat(plan.flatten()).anyMatch(node -> "Seq Scan".equals(node.nodeType()));
            List<PlanViolation> violations = PlanGuard.evaluate(plan,
                    PlanGuardPolicy.noSeqScan(10_000));
            assertThat(violations).anyMatch(v -> v.code().toString().equals("TQL-PLAN-1001"));
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                SQLSERVER.getJdbcUrl(), SQLSERVER.getUsername(), SQLSERVER.getPassword());
    }
}
