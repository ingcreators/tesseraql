package io.tesseraql.runtime;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The deadline sweeper's per-task savepoint against a live database (docs/audit-low-leads.md
 * slice 2b, G35). PostgreSQL is where the fence earns its keep: any error aborts the transaction
 * there, so one task's failing resolver would take every other overdue task's escalation down
 * with it — which is what happened, sweep after sweep, before the fence. This is the
 * per-pull-request proof; the gated portability suites run the same shared check on MySQL,
 * Oracle and SQL Server, where the savepoint's <em>release</em> is what differs.
 */
@Testcontainers
class WorkflowSweepFenceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void aFailingTaskIsFencedAndTheNextStillEscalates() throws Exception {
        DialectRuntimeChecks.workflowSweepRoundTrip(dataSource(), "postgresql");
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
