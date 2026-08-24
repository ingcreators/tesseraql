package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.dialect.SqlErrorKind;
import io.tesseraql.core.dialect.SqlErrors;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

/**
 * Contract SQL — the 2-way SQL a deployment supplies to satisfy a framework contract — was executed
 * by three hand-rolled paths that had diverged (docs/contract-sql-execution.md). These assert what
 * the shared primitive does that two of those three did not: it bounds the statement, and it hands
 * the caller a classified failure instead of a driver's raw one.
 */
class SqlStatementTest {

    private static final String SELECT = "select name from t where id = /*id*/'x'";

    @Test
    void boundsEveryStatementByTheSameDefaultARouteRunsUnder() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        SqlStatement.on(database.dataSource()).query("contract", SELECT, Map.of("id", "u1"));

        assertThat(database.calls).contains("setQueryTimeout(30)");
        assertThat(SqlStatement.DEFAULT_TIMEOUT_SECONDS).isEqualTo(30);
    }

    @Test
    void bindsTheRenderedParameters() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        List<Map<String, Object>> rows = SqlStatement.on(database.dataSource())
                .query("contract", SELECT, Map.of("id", "u1"));

        assertThat(rows).containsExactly(Map.of("name", "Anne"));
        assertThat(database.calls).contains("setObject(1,u1)");
    }

    @Test
    void anExplicitZeroOptsOutOfTheBound() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        SqlStatement.on(database.dataSource()).timeoutSeconds(0)
                .query("contract", SELECT, Map.of("id", "u1"));

        assertThat(database.calls).noneMatch(call -> call.startsWith("setQueryTimeout"));
    }

    @Test
    void boundsAWriteToo() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());

        int affected = SqlStatement.on(database.dataSource()).timeoutSeconds(7)
                .update("contract", "delete from t where id = /*id*/'x'", Map.of("id", "u1"));

        assertThat(affected).isEqualTo(1);
        assertThat(database.calls).contains("setQueryTimeout(7)");
    }

    @Test
    void classifiesTheDriversAnswerAndNamesTheContractThatAsked() {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.failure = new SQLException("duplicate key value", "23505");

        assertThatThrownBy(() -> SqlStatement.on(database.dataSource())
                .query("scim.users.create", SELECT, Map.of("id", "u1")))
                .isInstanceOf(SqlStatementException.class)
                .isInstanceOf(SQLException.class)
                .hasMessage("duplicate key value")
                .satisfies(thrown -> {
                    SqlStatementException failed = (SqlStatementException) thrown;
                    assertThat(failed.kind()).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
                    assertThat(failed.sqlId()).isEqualTo("scim.users.create");
                    // SQLState and vendor code travel with it, so a caller still asking the
                    // question the old way gets the same answer.
                    assertThat(SqlErrors.isUniqueViolation(failed)).isTrue();
                });
    }

    @Test
    void aForeignKeyViolationIsNotAUniqueOne() {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.failure = new SQLException("insert or update violates foreign key", "23503");

        assertThatThrownBy(() -> SqlStatement.on(database.dataSource())
                .update("scim.groups.addMember", SELECT, Map.of("id", "u1")))
                .asInstanceOf(InstanceOfAssertFactories.type(SqlStatementException.class))
                .extracting(SqlStatementException::kind)
                .isEqualTo(SqlErrorKind.FOREIGN_KEY_VIOLATION);
    }

    @Test
    void readsLabelsAsTheDriverReportsThemUnlessADialectSaysOtherwise()
            throws SqlStatementException {
        FakeDatabase upper = new FakeDatabase(List.of("USER_ID"), List.of("u1"));
        FakeDatabase quoted = new FakeDatabase(List.of("displayName"), List.of("Anne"));

        assertThat(SqlStatement.on(upper.dataSource()).dialect("oracle")
                .queryOne("contract", SELECT, Map.of("id", "u1")))
                .containsOnlyKeys("user_id");
        // A SCIM contract quotes its camelCase aliases on every dialect, and a quoted alias passes
        // Oracle's folding untouched — which is why an executor with no dialect loses nothing.
        assertThat(SqlStatement.on(quoted.dataSource())
                .queryOne("contract", SELECT, Map.of("id", "u1")))
                .containsOnlyKeys("displayName");
    }

    @Test
    void aReadThatMatchesNothingIsNoRowRatherThanAFailure() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of());

        assertThat(SqlStatement.on(database.dataSource())
                .queryOne("contract", SELECT, Map.of("id", "u1"))).isNull();
    }

    @Test
    void aDeclaredKeyReachesTheDriverAsColumnNamesWhereTheDialectHonoursThem()
            throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of("id"), List.of(7));

        SqlStatement.WriteResult written = SqlStatement.on(database.dataSource())
                .dialect("postgres")
                .update("scim.users.create", "insert into t (name) values (/*name*/'x')",
                        Map.of("name", "Anne"), List.of("id"));

        assertThat(written.affectedRows()).isEqualTo(1);
        assertThat(written.keys()).containsEntry("id", 7);
        // PostgreSQL honours requested key columns, so the prepare names them.
        assertThat(database.calls)
                .anyMatch(call -> call.startsWith("prepareStatement(") && call.endsWith(",[id])"));
    }

    @Test
    void aDialectWithoutKeyColumnsFallsBackToTheDriversGeneratedKeys()
            throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of("GENERATED_KEY"), List.of(7));

        SqlStatement.WriteResult written = SqlStatement.on(database.dataSource())
                .dialect("mysql")
                .update("scim.users.create", "insert into t (name) values (/*name*/'x')",
                        Map.of("name", "Anne"), List.of("id"));

        // MySQL hands back only the identity value; the declared name maps by position.
        assertThat(written.keys()).containsEntry("id", 7);
        assertThat(database.calls)
                .anyMatch(call -> call.startsWith("prepareStatement(") && call.endsWith(",1)"));
    }

    @Test
    void aWriteDeclaringNoKeysAsksTheDriverForNone() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());

        int affected = SqlStatement.on(database.dataSource())
                .update("contract", "delete from t where id = /*id*/'x'", Map.of("id", "u1"));

        assertThat(affected).isEqualTo(1);
        assertThat(database.calls).noneMatch(call -> call.equals("getGeneratedKeys"));
    }

    @Test
    void rawLabelsKeepTheDriversLabelsEvenUnderADialect() throws SqlStatementException {
        FakeDatabase upper = new FakeDatabase(List.of("USER_ID"), List.of("u1"));

        // The dialect steers the generated-key branch; the declared label policy stays raw
        // (docs/contract-sql-execution.md structural decision 7).
        assertThat(SqlStatement.on(upper.dataSource()).dialect("oracle").rawLabels()
                .queryOne("contract", SELECT, Map.of("id", "u1")))
                .containsOnlyKeys("USER_ID");
    }

    @Test
    void aTransactionCommitsWhatItsBodyWrote() throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        SqlStatement statements = SqlStatement.on(database.dataSource());

        int affected = statements.transact("scim.groups.create", connection -> statements
                .update(connection, "scim.groups.addMember",
                        "insert into m (g) values (/*g*/'x')", Map.of("g", "g1")));

        assertThat(affected).isEqualTo(1);
        assertThat(database.calls).contains("setAutoCommit(false)", "commit");
        assertThat(database.calls).doesNotContain("rollback");
    }

    @Test
    void aFailureInsideTheTransactionRollsItBackAndKeepsItsStatementsName() {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.failure = new SQLException("value too long", "22001");
        SqlStatement statements = SqlStatement.on(database.dataSource());

        assertThatThrownBy(() -> statements.transact("scim.groups.create",
                connection -> statements.update(connection, "scim.groups.addMember",
                        "insert into m (g) values (/*g*/'x')", Map.of("g", "g1"))))
                .asInstanceOf(InstanceOfAssertFactories.type(SqlStatementException.class))
                .extracting(SqlStatementException::sqlId)
                .isEqualTo("scim.groups.addMember");
        assertThat(database.calls).contains("rollback");
        assertThat(database.calls).doesNotContain("commit");
    }

    @Test
    void aCommittedTransactionIsNotReReportedOverAFailedAutocommitRestore()
            throws SqlStatementException {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.restoreFailure = new SQLException("connection is dead");
        SqlStatement statements = SqlStatement.on(database.dataSource());

        // The commit decided the outcome; a restore that fails afterwards must not turn a
        // durable write into a reported failure (the caller's retry would duplicate it).
        String result = statements.transact("scim.groups.create", connection -> "committed");

        assertThat(result).isEqualTo("committed");
        assertThat(database.calls).contains("commit");
    }

    @Test
    void everyStatementOpensTheSharedSpanWithTheContractSurface() throws SqlStatementException {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        SqlStatement.on(database.dataSource()).tracer(tracer)
                .query("scim.users.list", SELECT, Map.of("id", "u1"));

        assertThat(tracer.spans()).hasSize(1);
        io.tesseraql.core.telemetry.RecordingTracer.RecordedSpan span = tracer.spans().get(0);
        assertThat(span.name()).isEqualTo("tesseraql.sql.execute");
        assertThat(span.attributes())
                .containsEntry("surface", "contract")
                .containsEntry("sqlId", "scim.users.list")
                .containsEntry("rowCount", 1);
        assertThat(span.error()).isFalse();
    }

    @Test
    void aFailedStatementRecordsItsErrorOnTheSpan() {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.failure = new SQLException("duplicate key value", "23505");

        assertThatThrownBy(() -> SqlStatement.on(database.dataSource()).tracer(tracer)
                .update("scim.users.create", SELECT, Map.of("id", "u1")))
                .isInstanceOf(SqlStatementException.class);

        assertThat(tracer.spans()).hasSize(1);
        assertThat(tracer.spans().get(0).error()).isTrue();
        assertThat(tracer.spans().get(0).attributes())
                .containsEntry("sqlId", "scim.users.create");
    }

    @Test
    void aSpannedReaderStampsWhatOnlyItKnowsOntoTheStatementsSpan() throws Exception {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        Integer count = SqlStatement.onCallerConnections().tracer(tracer)
                .read(database.dataSource().getConnection(), "web/api/users.sql", bound,
                        (resultSet, span) -> {
                            int rows = 0;
                            while (resultSet.next()) {
                                rows++;
                            }
                            span.attribute("rowCount", rows);
                            return rows;
                        });

        assertThat(count).isEqualTo(1);
        assertThat(tracer.spans()).hasSize(1);
        assertThat(tracer.spans().get(0).attributes())
                .containsEntry("sqlId", "web/api/users.sql")
                .containsEntry("rowCount", 1);
    }

    @Test
    void aReaderRefusalIsRecordedOnTheStatementsSpan() throws Exception {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        assertThatThrownBy(() -> SqlStatement.onCallerConnections().tracer(tracer)
                .read(database.dataSource().getConnection(), "web/api/users.sql", bound,
                        (resultSet, span) -> {
                            throw new IllegalStateException("row cap");
                        }))
                .isInstanceOf(IllegalStateException.class);

        // The refusal came from the caller-owned read, but it is this statement's failure: the
        // span must not end clean under it.
        assertThat(tracer.spans()).hasSize(1);
        assertThat(tracer.spans().get(0).error()).isTrue();
    }

    @Test
    void cappedRowsAsksTheCallerAboutTheRowPastTheCapAndTruncatesWhenItReturns()
            throws Exception {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));
        List<String> overflowed = new ArrayList<>();

        List<Map<String, Object>> rows = SqlStatement.onCallerConnections()
                .read(database.dataSource().getConnection(), "web/api/users.sql", bound,
                        SqlStatement.cappedRows(null, 0, () -> overflowed.add("asked")));

        assertThat(rows).isEmpty();
        assertThat(overflowed).containsExactly("asked");
    }

    @Test
    void cappedRowsPropagatesTheCallersRefusal() {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        assertThatThrownBy(() -> SqlStatement.onCallerConnections()
                .read(database.dataSource().getConnection(), "web/api/users.sql", bound,
                        SqlStatement.cappedRows(null, 0, () -> {
                            throw new IllegalStateException("exceeds maxRows");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds maxRows");
    }

    @Test
    void aFrameworkBuiltReadBindsPositionalValuesUnderTheBound() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        Boolean found = SqlStatement.onCallerConnections()
                .read(database.dataSource().getConnection(), "workflow.document",
                        "select * from t where id = ?",
                        java.util.Arrays.asList((Object) "o1"),
                        (resultSet, span) -> resultSet.next());

        assertThat(found).isTrue();
        assertThat(database.calls).contains("setObject(1,o1)", "setQueryTimeout(30)");
    }

    @Test
    void aCallerRenderedWriteCapturesItsDeclaredKeys() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of("id"), List.of(7));
        BoundSql bound = SqlRenderer.render("insert into t (name) values (/*name*/'x')",
                Map.of("name", "Anne"));

        SqlStatement.WriteResult written = SqlStatement.onCallerConnections().dialect("postgres")
                .update(database.dataSource().getConnection(), "orders/create.sql", bound,
                        List.of("id"));

        assertThat(written.affectedRows()).isEqualTo(1);
        assertThat(written.keys()).containsEntry("id", 7);
        assertThat(database.calls)
                .anyMatch(call -> call.startsWith("prepareStatement(") && call.endsWith(",[id])"));
    }

    @Test
    void aCallerConnectionsExecutorRefusesToOpenItsOwn() {
        assertThatThrownBy(() -> SqlStatement.onCallerConnections()
                .query("contract", SELECT, Map.of("id", "u1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller-supplied");
    }

    @Test
    void aDeclaredAttributeRidesEverySpanTheExecutorOpens() throws Exception {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        SqlStatement statements = SqlStatement.on(database.dataSource()).tracer(tracer)
                .surface("job")
                .attribute("stepId", "deactivatePending");
        statements.read("jobs/maintenance.sql", bound, (resultSet, span) -> resultSet.next());
        statements.update("jobs/maintenance.sql", bound);

        assertThat(tracer.spans()).hasSize(2)
                .allSatisfy(span -> assertThat(span.attributes())
                        .containsEntry("surface", "job")
                        .containsEntry("stepId", "deactivatePending"));
    }

    @Test
    void aFetchSizeReadPreparesForwardOnlyAndCursorsAtThatSize() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        SqlStatement.on(database.dataSource()).fetchSize(500)
                .read("web/api/export.sql", bound, (resultSet, span) -> resultSet.next());

        // TYPE_FORWARD_ONLY=1003, CONCUR_READ_ONLY=1007: the streaming prepare an export uses
        // so the driver cursors instead of buffering the whole result.
        assertThat(database.calls)
                .anyMatch(call -> call.startsWith("prepareStatement(")
                        && call.endsWith(",1003,1007)"))
                .contains("setFetchSize(500)");
    }

    @Test
    void theMysqlRowStreamingSignalReachesTheDriverUnclamped() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        SqlStatement.on(database.dataSource())
                .fetchSize(io.tesseraql.core.dialect.StreamingProfiles.forDialect("mysql")
                        .fetchSize())
                .read("web/api/export.sql", bound, (resultSet, span) -> resultSet.next());

        // MySQL/MariaDB stream row-by-row only on setFetchSize(Integer.MIN_VALUE); anything
        // else buffers the whole result in the driver.
        assertThat(database.calls)
                .anyMatch(call -> call.startsWith("prepareStatement(")
                        && call.endsWith(",1003,1007)"))
                .contains("setFetchSize(" + Integer.MIN_VALUE + ")");
    }

    @Test
    void aDefaultReadKeepsTheDriversPlainPrepare() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));
        BoundSql bound = SqlRenderer.render(SELECT, Map.of("id", "u1"));

        SqlStatement.on(database.dataSource())
                .read("web/api/users.sql", bound, (resultSet, span) -> resultSet.next());

        assertThat(database.calls).noneMatch(call -> call.startsWith("setFetchSize"));
    }

    @Test
    void aDataSourceLevelWriteOpensItsOwnConnectionPerStatement() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        BoundSql bound = SqlRenderer.render("delete from t where id = /*id*/'x'",
                Map.of("id", "u1"));

        int affected = SqlStatement.on(database.dataSource())
                .update("web/api/users/delete.sql", bound);

        assertThat(affected).isEqualTo(1);
        assertThat(database.calls).contains("getConnection", "setQueryTimeout(30)");
    }

    @Test
    void aRowsHandlePreparesOncePerRenderedSqlText() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        BoundSql bound = SqlRenderer.render("delete from t where id = /*id*/'x'",
                Map.of("id", "u1"));

        try (SqlStatement.Rows rows = SqlStatement.onCallerConnections()
                .rows(database.dataSource().getConnection(), "writer.sql")) {
            rows.execute(bound);
            rows.execute(bound);
        }

        assertThat(database.calls.stream().filter(call -> call.startsWith("prepareStatement(")))
                .hasSize(1);
        assertThat(database.calls.stream().filter(call -> call.equals("execute"))).hasSize(2);
    }

    @Test
    void aRowsHandleFlushesInRowOrderWhenTheRenderedSqlChanges() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        BoundSql first = SqlRenderer.render("insert into t (a) values (/*a*/'x')",
                Map.of("a", "1"));
        BoundSql second = SqlRenderer.render("update t set a = /*a*/'x'", Map.of("a", "2"));

        try (SqlStatement.Rows rows = SqlStatement.onCallerConnections()
                .rows(database.dataSource().getConnection(), "writer.sql")) {
            rows.add(first);
            rows.add(second);
            rows.add(first);
            rows.flush();
        }

        // Order is row order: the pending batch flushes whenever the incoming row's SQL
        // differs from it, so an insert and its update never swap places.
        assertThat(database.calls.stream()
                .filter(call -> call.equals("addBatch") || call.equals("executeBatch")))
                .containsExactly("addBatch", "executeBatch", "addBatch", "executeBatch",
                        "addBatch", "executeBatch");
    }

    @Test
    void aFlushIsOneStatementAndOpensOneBatchSpan() throws Exception {
        io.tesseraql.core.telemetry.RecordingTracer tracer = new io.tesseraql.core.telemetry.RecordingTracer();
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        BoundSql bound = SqlRenderer.render("insert into t (a) values (/*a*/'x')",
                Map.of("a", "1"));

        try (SqlStatement.Rows rows = SqlStatement.onCallerConnections().tracer(tracer)
                .rows(database.dataSource().getConnection(), "writer.sql")) {
            rows.add(bound);
            rows.add(bound);
            assertThat(rows.flush()).isEqualTo(2);
        }

        assertThat(tracer.spans()).hasSize(1);
        assertThat(tracer.spans().get(0).attributes())
                .containsEntry("mode", "batch")
                .containsEntry("batchSize", 2)
                .containsEntry("affectedRows", 2);
    }

    @Test
    void aRowsHandleRefusesToCloseWithQueuedRowsAndDiscardIsTheAbortPathsAnswer()
            throws Exception {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        BoundSql bound = SqlRenderer.render("insert into t (a) values (/*a*/'x')",
                Map.of("a", "1"));

        SqlStatement.Rows forgotten = SqlStatement.onCallerConnections()
                .rows(database.dataSource().getConnection(), "writer.sql");
        forgotten.add(bound);
        assertThatThrownBy(forgotten::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never flushed");

        SqlStatement.Rows aborted = SqlStatement.onCallerConnections()
                .rows(database.dataSource().getConnection(), "writer.sql");
        aborted.add(bound);
        aborted.discard();
        aborted.close();
        assertThat(database.calls).contains("clearBatch");
    }

    @Test
    void aCallRegistersOutBindSitesByPositionAndAnswersThemByName() throws Exception {
        FakeDatabase database = new FakeDatabase(List.of(), List.of(0, 42));
        BoundSql bound = SqlRenderer.render(
                "{call reprice(/* factor */1, /* out.doubled */null)}", Map.of("factor", 21));

        Map<String, Object> outs = SqlStatement.onCallerConnections()
                .call(database.dataSource().getConnection(), "orders/reprice.sql", bound,
                        Map.of("doubled", java.sql.Types.INTEGER));

        assertThat(outs).containsExactly(Map.entry("doubled", 42));
        // Types.INTEGER = 4: the declared keyword reached the driver at the rendered position.
        assertThat(database.calls)
                .anyMatch(call -> call.startsWith("prepareCall("))
                .contains("setObject(1,21)", "registerOutParameter(2,4)", "execute");
    }

    @Test
    void aCallRefusesTheDeclarationMismatchBothWays() {
        FakeDatabase database = new FakeDatabase(List.of(), List.of(0, 42));
        BoundSql bound = SqlRenderer.render(
                "{call reprice(/* factor */1, /* out.doubled */null)}", Map.of("factor", 21));

        assertThatThrownBy(() -> SqlStatement.onCallerConnections()
                .call(database.dataSource().getConnection(), "orders/reprice.sql", bound,
                        Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out: does not declare");
        assertThatThrownBy(() -> SqlStatement.onCallerConnections()
                .call(database.dataSource().getConnection(), "orders/reprice.sql", bound,
                        Map.of("doubled", java.sql.Types.INTEGER,
                                "never_rendered", java.sql.Types.INTEGER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never binds");
    }

    /** A JDBC stack that records what was asked of it and answers one row (or the failure set). */
    private static final class FakeDatabase implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private final List<String> labels;
        private final List<Object> row;
        private SQLException failure;
        private SQLException restoreFailure;
        private boolean rowRead;
        private int batched;
        private int autoCommitCalls;

        private FakeDatabase(List<String> labels, List<Object> row) {
            this.labels = labels;
            this.row = row;
        }

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) throws SQLException {
            calls.add(method.getName() + (args == null ? "" : "(" + join(args) + ")"));
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "prepareStatement" -> proxy(PreparedStatement.class);
                case "prepareCall" -> proxy(java.sql.CallableStatement.class);
                case "executeQuery" -> fail(proxy(ResultSet.class));
                case "executeUpdate" -> fail(1);
                case "execute" -> fail(Boolean.TRUE);
                case "getUpdateCount" -> 1;
                case "setAutoCommit" -> autoCommitSet();
                case "addBatch" -> queued();
                case "executeBatch" -> drained();
                case "clearBatch" -> cleared();
                case "getGeneratedKeys" -> proxy(ResultSet.class);
                case "getMetaData" -> proxy(ResultSetMetaData.class);
                case "getColumnCount" -> labels.size();
                case "getColumnLabel" -> labels.get((Integer) args[0] - 1);
                case "getObject" -> row.get((Integer) args[0] - 1);
                case "next" -> nextRow();
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object fail(Object answer) throws SQLException {
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        private Object autoCommitSet() throws SQLException {
            // The second call is transact's finally-restore; the first turned autocommit off.
            if (++autoCommitCalls >= 2 && restoreFailure != null) {
                throw restoreFailure;
            }
            return null;
        }

        private Object queued() {
            batched++;
            return null;
        }

        private Object drained() throws SQLException {
            if (failure != null) {
                batched = 0;
                throw failure;
            }
            int[] counts = new int[batched];
            java.util.Arrays.fill(counts, 1);
            batched = 0;
            return counts;
        }

        private Object cleared() {
            batched = 0;
            return null;
        }

        private boolean nextRow() {
            boolean has = !row.isEmpty() && !rowRead;
            rowRead = true;
            return has;
        }

        private static String join(Object[] args) {
            StringBuilder text = new StringBuilder();
            for (Object arg : args) {
                text.append(text.isEmpty() ? "" : ",")
                        .append(arg instanceof Object[] array
                                ? "[" + String.join("|", java.util.Arrays.stream(array)
                                        .map(String::valueOf).toList()) + "]"
                                : arg);
            }
            return text.toString();
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }
    }
}
