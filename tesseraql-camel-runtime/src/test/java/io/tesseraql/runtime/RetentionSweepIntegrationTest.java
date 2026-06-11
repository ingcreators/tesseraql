package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.operations.retention.RetentionSweeper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the retention sweep (design ch. 44): delivered outbox events and finished
 * batch executions past their windows are removed; in-flight and recent rows stay.
 */
@Testcontainers
class RetentionSweepIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void sweepsExpiredRowsAndKeepsLiveOnes() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        new JobRepository(dataSource).ensureSchema();
        new JdbcOutboxStore(dataSource).ensureSchema();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // Outbox: an old delivered event (sweep), a recent delivered one and a pending one (keep).
            statement.execute(outbox("old-sent", "SENT", "now() - interval '60 days'"));
            statement.execute(outbox("new-sent", "SENT", "now()"));
            statement.execute(outbox("pending", "PENDING", "null"));
            // Jobs: an old finished execution with a step (sweep), a recent and a running one (keep).
            statement.execute(execution("old-done", "COMPLETED", "now() - interval '120 days'"));
            statement.execute("insert into tql_step_execution (step_execution_id, job_execution_id, "
                    + "step_id, status, start_time) values ('s1','old-done','load','COMPLETED', now())");
            statement.execute(execution("new-done", "COMPLETED", "now()"));
            statement.execute(execution("running", "RUNNING", "null"));
        }

        RetentionSweeper.Result result = new RetentionSweeper(dataSource)
                .sweep(Duration.ofDays(30), Duration.ofDays(90));

        assertThat(result.outboxEvents()).isEqualTo(1);
        assertThat(result.jobExecutions()).isEqualTo(1);
        assertThat(result.stepExecutions()).isEqualTo(1);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(ids(statement, "select event_id from tql_outbox_event order by event_id"))
                    .containsExactly("new-sent", "pending");
            assertThat(ids(statement,
                    "select job_execution_id from tql_job_execution order by job_execution_id"))
                    .containsExactly("new-done", "running");
        }
    }

    private static String outbox(String id, String status, String sentAt) {
        return "insert into tql_outbox_event "
                + "(event_id, event_type, status, created_at, sent_at, app_name) "
                + "values ('" + id + "', 'USER_CREATED', '" + status + "', now(), " + sentAt
                + ", 'user-admin')";
    }

    private static String execution(String id, String status, String endTime) {
        return "insert into tql_job_execution (job_execution_id, job_id, app_name, status, "
                + "start_time, end_time, created_at) values ('" + id + "', 'nightly', "
                + "'user-admin', '" + status + "', now(), " + endTime + ", now())";
    }

    private static java.util.List<String> ids(Statement statement, String sql) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
