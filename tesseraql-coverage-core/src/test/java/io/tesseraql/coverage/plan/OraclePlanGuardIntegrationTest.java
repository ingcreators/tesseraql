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
import org.testcontainers.oracle.OracleContainer;

/**
 * Live Query Plan Guard test on Oracle (design ch. 42, 46): real {@code EXPLAIN PLAN} /
 * {@code PLAN_TABLE} rows normalize so the same guard rules apply across dialects. Gated behind
 * {@code -Dtesseraql.dialect.its=true} because the Oracle Free image is large; the normalization
 * itself stays covered by the always-on unit tests.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tesseraql.dialect.its", matches = "true")
class OraclePlanGuardIntegrationTest {

    @Container
    static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

    private static final OraclePlanInspector INSPECTOR = new OraclePlanInspector();

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table t (id number primary key, name varchar2(64), val number)");
            statement.execute("insert into t select level, 'n' || level, level"
                    + " from dual connect by level <= 500");
            statement.execute(
                    "begin dbms_stats.gather_table_stats(user, 'T'); end;");
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
            List<PlanViolation> violations =
                    PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000));
            assertThat(violations).anyMatch(v -> v.code().toString().equals("TQL-PLAN-1001"));
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
    }
}
