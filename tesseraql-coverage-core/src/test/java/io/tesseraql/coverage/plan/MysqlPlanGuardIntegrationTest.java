package io.tesseraql.coverage.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the Query Plan Guard on MySQL (design ch. 42, 46): real MySQL
 * {@code EXPLAIN FORMAT=JSON} output is normalized so the same guard rules apply across dialects.
 */
@Testcontainers
class MysqlPlanGuardIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private static final MysqlPlanInspector INSPECTOR = new MysqlPlanInspector();

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table t (id int primary key auto_increment, name varchar(64), val int)");
            for (int i = 1; i <= 500; i++) {
                statement.execute("insert into t (name, val) values ('n" + i + "', " + i + ")");
            }
            statement.execute("analyze table t");
        }
    }

    @Test
    void primaryKeyLookupIsNotASeqScan() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection, "select * from t where id = ?",
                    List.of(250));
            assertThat(plan.flatten()).noneMatch(node -> "Seq Scan".equals(node.nodeType()));
            assertThat(PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000))).isEmpty();
        }
    }

    @Test
    void fullScanIsRejected() throws Exception {
        try (Connection connection = connection()) {
            QueryPlan plan = INSPECTOR.explain(connection, "select * from t where name = ?",
                    List.of("n1"));
            assertThat(plan.flatten()).anyMatch(node -> "Seq Scan".equals(node.nodeType()));
            List<PlanViolation> violations = PlanGuard.evaluate(plan,
                    PlanGuardPolicy.noSeqScan(10_000));
            assertThat(violations).anyMatch(v -> v.code().toString().equals("TQL-PLAN-1001"));
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
    }
}
