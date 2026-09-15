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
 *
 * <p>The last case pins the escalation reminder's envelope (docs/audit-low-leads.md G34): the
 * declared {@code recipient:} and the task's tenant ride it, so an inbox channel can deliver.
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

    @Test
    void theEscalationReminderIsAddressedToTheNewAssigneeInTheTasksTenant() {
        FakeJdbc jdbc = new FakeJdbc();
        List<io.tesseraql.core.outbox.OutboxEvent> enqueued = new ArrayList<>();
        io.tesseraql.core.outbox.OutboxStore outbox = (io.tesseraql.core.outbox.OutboxStore) Proxy
                .newProxyInstance(WorkflowSweeperBoundTest.class.getClassLoader(),
                        new Class<?>[]{io.tesseraql.core.outbox.OutboxStore.class},
                        (instance, method, args) -> {
                            if (method.getName().equals("insert")) {
                                enqueued.add((io.tesseraql.core.outbox.OutboxEvent) args[1]);
                                return "evt-1";
                            }
                            return null;
                        });
        io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify reminder = io.tesseraql.yaml.notify.NotifyEvents
                .compile("order", "escalated", new io.tesseraql.yaml.model.NotifySpec(
                        "task-inbox", null, "assignee", null, java.util.Map.of("to", "assignee"),
                        null, null, null, null));

        int escalated = sweeper(jdbc, new WorkflowSweeper.Rule("order", "review",
                Sql2WayParser.parse("select fallback from resolvers where doc = /*docId*/'x'",
                        ExpressionFunctions.processDefault()),
                null, reminder), outbox, "acme")
                .sweep();

        assertThat(escalated).isEqualTo(1);
        // The resolver answered "bob"; the envelope names them and the task's tenant.
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).payloadJson())
                .contains("\"recipient\":\"bob\"")
                .contains("\"tenant\":\"acme\"")
                .contains("\"source\":\"order.escalated\"");
    }

    private static WorkflowSweeper sweeper(FakeJdbc jdbc, WorkflowSweeper.Rule rule) {
        return sweeper(jdbc, rule, null, null);
    }

    private static WorkflowSweeper sweeper(FakeJdbc jdbc, WorkflowSweeper.Rule rule,
            io.tesseraql.core.outbox.OutboxStore outbox, String tenantId) {
        WorkflowTaskStore taskStore = (WorkflowTaskStore) Proxy.newProxyInstance(
                WorkflowSweeperBoundTest.class.getClassLoader(),
                new Class<?>[]{WorkflowTaskStore.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "overdue" -> List.of(new WorkflowTaskStore.Overdue("t1", "order", "d1",
                            "review", "alice", tenantId));
                    default -> null;
                });
        return new WorkflowSweeper(List.of(rule), taskStore, null, outbox, "app",
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
