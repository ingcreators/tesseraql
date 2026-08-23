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

    /** A JDBC stack that records what was asked of it and answers one row (or the failure set). */
    private static final class FakeDatabase implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private final List<String> labels;
        private final List<Object> row;
        private SQLException failure;
        private boolean rowRead;

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
                case "executeQuery" -> fail(proxy(ResultSet.class));
                case "executeUpdate" -> fail(1);
                case "execute" -> fail(Boolean.TRUE);
                case "getUpdateCount" -> 1;
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
