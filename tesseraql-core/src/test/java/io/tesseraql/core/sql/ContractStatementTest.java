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
class ContractStatementTest {

    private static final String SELECT = "select name from t where id = /*id*/'x'";

    @Test
    void boundsEveryStatementByTheSameDefaultARouteRunsUnder() throws ContractSqlException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        ContractStatement.on(database.dataSource()).query("contract", SELECT, Map.of("id", "u1"));

        assertThat(database.calls).contains("setQueryTimeout(30)");
        assertThat(ContractStatement.DEFAULT_TIMEOUT_SECONDS).isEqualTo(30);
    }

    @Test
    void bindsTheRenderedParameters() throws ContractSqlException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        List<Map<String, Object>> rows = ContractStatement.on(database.dataSource())
                .query("contract", SELECT, Map.of("id", "u1"));

        assertThat(rows).containsExactly(Map.of("name", "Anne"));
        assertThat(database.calls).contains("setObject(1,u1)");
    }

    @Test
    void anExplicitZeroOptsOutOfTheBound() throws ContractSqlException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of("Anne"));

        ContractStatement.on(database.dataSource()).timeoutSeconds(0)
                .query("contract", SELECT, Map.of("id", "u1"));

        assertThat(database.calls).noneMatch(call -> call.startsWith("setQueryTimeout"));
    }

    @Test
    void boundsAWriteToo() throws ContractSqlException {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());

        int affected = ContractStatement.on(database.dataSource()).timeoutSeconds(7)
                .update("contract", "delete from t where id = /*id*/'x'", Map.of("id", "u1"));

        assertThat(affected).isEqualTo(1);
        assertThat(database.calls).contains("setQueryTimeout(7)");
    }

    @Test
    void classifiesTheDriversAnswerAndNamesTheContractThatAsked() {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.failure = new SQLException("duplicate key value", "23505");

        assertThatThrownBy(() -> ContractStatement.on(database.dataSource())
                .query("scim.users.create", SELECT, Map.of("id", "u1")))
                .isInstanceOf(ContractSqlException.class)
                .isInstanceOf(SQLException.class)
                .hasMessage("duplicate key value")
                .satisfies(thrown -> {
                    ContractSqlException failed = (ContractSqlException) thrown;
                    assertThat(failed.kind()).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
                    assertThat(failed.contract()).isEqualTo("scim.users.create");
                    // SQLState and vendor code travel with it, so a caller still asking the
                    // question the old way gets the same answer.
                    assertThat(SqlErrors.isUniqueViolation(failed)).isTrue();
                });
    }

    @Test
    void aForeignKeyViolationIsNotAUniqueOne() {
        FakeDatabase database = new FakeDatabase(List.of(), List.of());
        database.failure = new SQLException("insert or update violates foreign key", "23503");

        assertThatThrownBy(() -> ContractStatement.on(database.dataSource())
                .update("scim.groups.addMember", SELECT, Map.of("id", "u1")))
                .asInstanceOf(InstanceOfAssertFactories.type(ContractSqlException.class))
                .extracting(ContractSqlException::kind)
                .isEqualTo(SqlErrorKind.FOREIGN_KEY_VIOLATION);
    }

    @Test
    void readsLabelsAsTheDriverReportsThemUnlessADialectSaysOtherwise()
            throws ContractSqlException {
        FakeDatabase upper = new FakeDatabase(List.of("USER_ID"), List.of("u1"));
        FakeDatabase quoted = new FakeDatabase(List.of("displayName"), List.of("Anne"));

        assertThat(ContractStatement.on(upper.dataSource()).dialect("oracle")
                .queryOne("contract", SELECT, Map.of("id", "u1")))
                .containsOnlyKeys("user_id");
        // A SCIM contract quotes its camelCase aliases on every dialect, and a quoted alias passes
        // Oracle's folding untouched — which is why an executor with no dialect loses nothing.
        assertThat(ContractStatement.on(quoted.dataSource())
                .queryOne("contract", SELECT, Map.of("id", "u1")))
                .containsOnlyKeys("displayName");
    }

    @Test
    void aReadThatMatchesNothingIsNoRowRatherThanAFailure() throws ContractSqlException {
        FakeDatabase database = new FakeDatabase(List.of("name"), List.of());

        assertThat(ContractStatement.on(database.dataSource())
                .queryOne("contract", SELECT, Map.of("id", "u1"))).isNull();
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
                text.append(text.isEmpty() ? "" : ",").append(arg);
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
