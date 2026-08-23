package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The sweeper ran an application's reassign resolver and escalate command with no statement
 * bound at all, inside the sweep's own transaction (docs/contract-sql-execution.md slice 2).
 * These pin the bound on each statement; reverting the {@code applyTimeout} calls turns them red.
 */
class WorkflowSweeperBoundTest {

    @Test
    void boundsTheReassignResolver() {
        FakeJdbc jdbc = new FakeJdbc();

        int escalated = sweeper(jdbc, new WorkflowSweeper.Rule("order", "review",
                Sql2WayParser.parse("select fallback from resolvers where doc = /*docId*/'x'",
                        ExpressionFunctions.processDefault()),
                null, null))
                .sqlTimeoutSeconds(9)
                .sweep();

        assertThat(escalated).isEqualTo(1);
        assertThat(jdbc.calls).contains("setQueryTimeout(9)");
    }

    @Test
    void boundsTheColumnAdvanceAndTheEscalateCommand() {
        FakeJdbc jdbc = new FakeJdbc();

        sweeper(jdbc, new WorkflowSweeper.Rule("order", "review", null,
                new WorkflowSweeper.Escalate("expire", "expired",
                        Sql2WayParser.parse(
                                "update orders set status = 'expired' where id = /*key*/'x'",
                                ExpressionFunctions.processDefault()),
                        false, "orders", "status", "order_id"),
                null))
                .sqlTimeoutSeconds(9)
                .sweep();

        assertThat(jdbc.calls.stream().filter(call -> call.equals("setQueryTimeout(9)")))
                .hasSize(2);
    }

    @Test
    void anExplicitZeroOptsOutOfTheBound() {
        FakeJdbc jdbc = new FakeJdbc();

        sweeper(jdbc, new WorkflowSweeper.Rule("order", "review",
                Sql2WayParser.parse("select fallback from resolvers where doc = /*docId*/'x'",
                        ExpressionFunctions.processDefault()),
                null, null))
                .sqlTimeoutSeconds(0)
                .sweep();

        assertThat(jdbc.calls).noneMatch(call -> call.startsWith("setQueryTimeout"));
    }

    private static WorkflowSweeper sweeper(FakeJdbc jdbc, WorkflowSweeper.Rule rule) {
        WorkflowTaskStore taskStore = (WorkflowTaskStore) Proxy.newProxyInstance(
                WorkflowSweeperBoundTest.class.getClassLoader(),
                new Class<?>[]{WorkflowTaskStore.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "overdue" -> List.of(new WorkflowTaskStore.Overdue("t1", "order", "d1",
                            "review", "alice"));
                    default -> null;
                });
        return new WorkflowSweeper(List.of(rule), taskStore, null, null, "app",
                jdbc.dataSource(), null);
    }

    /** A JDBC stack that records what was asked of it and answers one resolver row. */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private boolean rowRead;

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            calls.add(method.getName() + (args == null || args.length == 0
                    ? ""
                    : "(" + args[0] + ")"));
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "prepareStatement" -> proxy(PreparedStatement.class);
                case "executeQuery" -> proxy(ResultSet.class);
                case "executeUpdate" -> 1;
                case "execute" -> Boolean.TRUE;
                case "getUpdateCount" -> 1;
                case "next" -> nextRow();
                case "getString" -> "bob";
                default -> defaultValue(method.getReturnType());
            };
        }

        private boolean nextRow() {
            boolean has = !rowRead;
            rowRead = true;
            return has;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }
    }
}
