package io.tesseraql.coverage.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the Query Plan Guard (design ch. 46): real PostgreSQL EXPLAIN output is
 * normalized and evaluated against guard rules.
 */
@Testcontainers
class PlanGuardIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final PostgresPlanInspector INSPECTOR = new PostgresPlanInspector();

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table t (id serial primary key, name text, val int)");
            statement.execute("insert into t (name, val) "
                    + "select 'n' || g, g from generate_series(1, 1000) g");
            statement.execute("analyze t");
        }
    }

    @Test
    void indexedLookupPassesNoSeqScanPolicy() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection, "select * from t where id = ?",
                    List.of(500));
            List<PlanViolation> violations = PlanGuard.evaluate(plan,
                    PlanGuardPolicy.noSeqScan(100));
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void fullScanIsRejected() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection, "select * from t", List.of());
            List<PlanViolation> violations = PlanGuard.evaluate(plan,
                    PlanGuardPolicy.noSeqScan(10_000));
            assertThat(violations).anyMatch(v -> v.code().toString().equals("TQL-PLAN-1001"));
        }
    }

    @Test
    void estimatedRowsLimitIsEnforced() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection, "select * from t", List.of());
            PlanGuardPolicy policy = new PlanGuardPolicy(10, -1, true, java.util.Set.of());
            List<PlanViolation> violations = PlanGuard.evaluate(plan, policy);
            assertThat(violations).anyMatch(v -> v.code().toString().equals("TQL-PLAN-1002"));
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
