package io.tesseraql.yaml.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.decision.DecisionTables;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.workflow.WorkflowStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The transition engine ran an application's guard SQL, its stamp UPDATE and its document load
 * with no statement bound at all, inside the command's open transaction — while threading a
 * timeout it only ever applied to decisions (docs/contract-sql-execution.md slice 2). These pin
 * the bound on each formerly-unbounded statement; reverting the {@code applyTimeout} calls turns
 * them red.
 */
class TransitionExecutorBoundTest {

    @Test
    void boundsTheDocumentLoadAndTheGuardStatement() throws Exception {
        FakeJdbc jdbc = new FakeJdbc(List.of(false, false));

        assertThatThrownBy(() -> TransitionExecutor.begin(jdbc.connection(), transition(),
                collaborators(17), "o1", new LinkedHashMap<>()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("guard matched no rows");

        // One bound for the document load, one for the guard query that then matched no rows.
        assertThat(jdbc.calls.stream().filter(call -> call.equals("setQueryTimeout(17)")))
                .hasSize(2);
    }

    @Test
    void boundsTheStampUpdate() throws Exception {
        FakeJdbc jdbc = new FakeJdbc(List.of(false, true));

        TransitionExecutor.Session session = TransitionExecutor.begin(jdbc.connection(),
                transition(), collaborators(17), "o1", new LinkedHashMap<>());
        session.advance(jdbc.connection(), new LinkedHashMap<>());

        assertThat(jdbc.calls.stream().filter(call -> call.equals("setQueryTimeout(17)")))
                .hasSize(3);
        assertThat(jdbc.calls).contains("executeUpdate");
    }

    @Test
    void anExplicitZeroOptsOutOfTheBound() throws Exception {
        FakeJdbc jdbc = new FakeJdbc(List.of(false, true));

        TransitionExecutor.Session session = TransitionExecutor.begin(jdbc.connection(),
                transition(), collaborators(0), "o1", new LinkedHashMap<>());
        session.advance(jdbc.connection(), new LinkedHashMap<>());

        assertThat(jdbc.calls).noneMatch(call -> call.startsWith("setQueryTimeout"));
    }

    private static TransitionExecutor.CompiledTransition transition() {
        return new TransitionExecutor.CompiledTransition("wf", "approve", "order", "orders",
                "order_id", "draft", "approved", "draft", true, null,
                Sql2WayParser.parse("select 1 from orders where order_id = /*key*/'x'",
                        ExpressionFunctions.processDefault()),
                null, null, Map.of("approved_by", "system"),
                new DecisionTables(List.of()), null);
    }

    private static TransitionExecutor.Collaborators collaborators(int timeoutSeconds) {
        WorkflowStore store = (WorkflowStore) Proxy.newProxyInstance(
                TransitionExecutorBoundTest.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "currentState" -> "draft";
                    case "advanceState" -> 1;
                    default -> null;
                });
        return new TransitionExecutor.Collaborators(store, null, ScopeResolver.UNSUPPORTED,
                null, List.of(), timeoutSeconds, null);
    }

    /** A JDBC stack that records what was asked of it; {@code next()} answers from the queue. */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private final Deque<Boolean> nextAnswers;

        private FakeJdbc(List<Boolean> nextAnswers) {
            this.nextAnswers = new ArrayDeque<>(nextAnswers);
        }

        private Connection connection() {
            return proxy(Connection.class);
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
                case "prepareStatement" -> proxy(PreparedStatement.class);
                case "executeQuery" -> proxy(ResultSet.class);
                case "executeUpdate" -> 1;
                case "getMetaData" -> proxy(ResultSetMetaData.class);
                case "getColumnCount" -> 0;
                case "next" -> !nextAnswers.isEmpty() && nextAnswers.removeFirst();
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }
    }
}
