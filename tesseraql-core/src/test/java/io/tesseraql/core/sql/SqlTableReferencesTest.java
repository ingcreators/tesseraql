package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.sql.SqlTableReferences.Access;
import io.tesseraql.core.sql.SqlTableReferences.TableRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTableReferencesTest {

    @Test
    void aSimpleSelectReadsItsFromTable() {
        assertThat(SqlTableReferences.extract("SELECT id, name FROM users"))
                .containsExactly(new TableRef("users", Access.READ));
    }

    @Test
    void joinsAndCommaListsAreAllReads() {
        assertThat(SqlTableReferences.extract(
                "SELECT * FROM orders o JOIN customers c ON c.id = o.customer_id"))
                .containsExactly(new TableRef("customers", Access.READ),
                        new TableRef("orders", Access.READ));
        assertThat(SqlTableReferences.extract("SELECT * FROM orders o, customers c, regions r"))
                .containsExactly(new TableRef("customers", Access.READ),
                        new TableRef("orders", Access.READ), new TableRef("regions", Access.READ));
    }

    @Test
    void lockingAndUpsertUpdateClausesDoNotInventAWriteTable() {
        // `FOR UPDATE` is a row lock, not a DML UPDATE; the `OF` table is already a FROM read.
        assertThat(SqlTableReferences.extract("SELECT id FROM jobs FOR UPDATE OF jobs SKIP LOCKED"))
                .containsExactly(new TableRef("jobs", Access.READ));
        // MySQL upsert: `ON DUPLICATE KEY UPDATE col = …` updates the INSERT target, not table `col`.
        assertThat(SqlTableReferences.extract(
                "INSERT INTO counters (k, n) VALUES ('a', 1) ON DUPLICATE KEY UPDATE n = n + 1"))
                .containsExactly(new TableRef("counters", Access.WRITE));
    }

    @Test
    void schemaQualifiersAndQuotesAreNormalizedToBareNames() {
        assertThat(SqlTableReferences.extract("SELECT * FROM public.\"Users\""))
                .containsExactly(new TableRef("Users", Access.READ));
        assertThat(SqlTableReferences.extract("SELECT * FROM main.app.events"))
                .containsExactly(new TableRef("events", Access.READ));
    }

    @Test
    void insertTargetIsAWriteAndAnInsertSelectAlsoReads() {
        List<TableRef> refs = SqlTableReferences
                .extract("INSERT INTO audit (msg) SELECT note FROM events");
        assertThat(refs).containsExactly(new TableRef("audit", Access.WRITE),
                new TableRef("events", Access.READ));
    }

    @Test
    void updateTargetIsAWrite() {
        assertThat(SqlTableReferences
                .extract("UPDATE accounts SET balance = balance - 1 WHERE id = 7"))
                .containsExactly(new TableRef("accounts", Access.WRITE));
    }

    @Test
    void deleteFromTargetIsAWriteNotARead() {
        assertThat(SqlTableReferences.extract("DELETE FROM sessions WHERE expires < now()"))
                .containsExactly(new TableRef("sessions", Access.WRITE));
    }

    @Test
    void mergeWritesItsTargetAndReadsItsSource() {
        List<TableRef> refs = SqlTableReferences.extract(
                "MERGE INTO target t USING source s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.v = s.v");
        assertThat(refs).containsExactly(new TableRef("source", Access.READ),
                new TableRef("target", Access.WRITE));
    }

    @Test
    void commonTableExpressionNamesAreNotReportedAsTables() {
        List<TableRef> refs = SqlTableReferences.extract(
                "WITH recent AS (SELECT * FROM orders) SELECT * FROM recent JOIN customers ON true");
        assertThat(refs).containsExactly(new TableRef("customers", Access.READ),
                new TableRef("orders", Access.READ));
    }

    @Test
    void derivedTableSubqueriesContributeOnlyTheirInnerTables() {
        List<TableRef> refs = SqlTableReferences
                .extract("SELECT * FROM (SELECT id FROM events) e WHERE e.id > 0");
        assertThat(refs).containsExactly(new TableRef("events", Access.READ));
    }

    @Test
    void twoWayDirectivesAndCommentsAreStripped() {
        String sql = "SELECT * FROM users\n"
                + "-- a line comment naming orders\n"
                + "WHERE status = /* query.status */ 'ACTIVE'\n"
                + "/*%if query.since != null */ AND created > /* query.since */ '2020-01-01' /*%end*/";
        assertThat(SqlTableReferences.extract(sql))
                .containsExactly(new TableRef("users", Access.READ));
    }

    @Test
    void aTableBothWrittenAndReadAppearsUnderBothAccesses() {
        List<TableRef> refs = SqlTableReferences
                .extract("INSERT INTO ledger SELECT * FROM ledger WHERE posted = false");
        assertThat(refs).containsExactly(new TableRef("ledger", Access.READ),
                new TableRef("ledger", Access.WRITE));
    }

    @Test
    void joinUsingClauseDoesNotCaptureTheJoinColumn() {
        assertThat(SqlTableReferences.extract("SELECT * FROM a JOIN b USING (id)"))
                .containsExactly(new TableRef("a", Access.READ), new TableRef("b", Access.READ));
    }

    @Test
    void contractOrServiceBindingsWithNoSqlYieldNothing() {
        assertThat(SqlTableReferences.extract(null)).isEmpty();
        assertThat(SqlTableReferences.extract("   ")).isEmpty();
    }

    @Test
    void resultsAreDistinctAndDeterministicallyOrdered() {
        List<TableRef> refs = SqlTableReferences.extract(
                "SELECT * FROM zebra z JOIN alpha a ON true JOIN zebra z2 ON true");
        assertThat(refs).containsExactly(new TableRef("alpha", Access.READ),
                new TableRef("zebra", Access.READ));
    }
}
