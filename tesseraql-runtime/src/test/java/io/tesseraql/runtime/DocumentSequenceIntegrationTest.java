package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.sequence.JdbcDocumentSequences;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The managed document-number sequence against a live database (roadmap Phase 18). It had no test
 * of any kind, which is how a seed path that fails outright on two shipped dialects went unseen.
 *
 * <p>PostgreSQL is where the savepoint the seed path opens earns its keep: any error aborts the
 * transaction there, so a unique violation on the seed would take the caller's whole command down
 * without the fence. This is the per-pull-request proof that the fence still works; the gated
 * portability suites run the same shared check on MySQL, Oracle and SQL Server, where the
 * savepoint's <em>release</em> is what differs.
 */
@Testcontainers
class DocumentSequenceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void seedsAndAllocatesUnderContention() throws Exception {
        DialectRuntimeChecks.documentSequenceRoundTrip(dataSource());
    }

    @Test
    void allocatesGaplesslyOnOneConnection() throws Exception {
        JdbcDocumentSequences sequences = new JdbcDocumentSequences(dataSource());
        sequences.ensureSchema();

        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            assertThat(sequences.next(connection, "invoice")).isEqualTo(1L);
            assertThat(sequences.next(connection, "invoice")).isEqualTo(2L);
            // A second name is its own counter, seeded on its own first use.
            assertThat(sequences.next(connection, "receipt")).isEqualTo(1L);
            connection.commit();
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
