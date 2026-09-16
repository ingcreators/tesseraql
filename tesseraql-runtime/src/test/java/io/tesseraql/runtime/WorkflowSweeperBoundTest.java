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
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The sweeper ran an application's reassign resolver and escalate command with no statement
 * bound at all, inside the sweep's own transaction (docs/contract-sql-execution.md slice 2).
 * These pin the bound on each statement; reverting the {@code applyTimeout} calls turns them red.
 *
 * <p>The later cases pin the escalation reminder's envelope (docs/audit-low-leads.md G34): the
 * declared {@code recipient:} and the task's tenant ride it, so an inbox channel can deliver;
 * and the batch's isolation (G35, finder 7): one task's failure rolls back to its own
 * savepoint, is named at WARNING, and the next task still escalates — including a sweep-fired
 * command that matched no row, which is the route's refusal here too.
 */
class WorkflowSweeperBoundTest {

    @Test
    void boundsTheReassignResolver() {
        FakeJdbc jdbc = new FakeJdbc();

        int escalated = sweeper(jdbc, new WorkflowSweeper.Rule("order", "review",
                reassign("select fallback from resolvers where doc = /*docId*/'x'"),
                null, null, DOCUMENT))
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
                null, DOCUMENT))
                .sqlTimeoutSeconds(9)
                .sweep();

        assertThat(jdbc.calls.stream().filter(call -> call.equals("setQueryTimeout(9)")))
                .hasSize(2);
    }

    @Test
    void anExplicitZeroOptsOutOfTheBound() {
        FakeJdbc jdbc = new FakeJdbc();

        sweeper(jdbc, new WorkflowSweeper.Rule("order", "review",
                reassign("select fallback from resolvers where doc = /*docId*/'x'"),
                null, null, DOCUMENT))
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
                reassign("select fallback from resolvers where doc = /*docId*/'x'"),
                null, reminder, DOCUMENT), outbox, "acme")
                .sweep();

        assertThat(escalated).isEqualTo(1);
        // The resolver answered "bob"; the envelope names them and the task's tenant.
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).payloadJson())
                .contains("\"recipient\":\"bob\"")
                .contains("\"tenant\":\"acme\"")
                .contains("\"source\":\"order.escalated\"");
    }

    private static final WorkflowSweeper.Document DOCUMENT = new WorkflowSweeper.Document(
            "orders", "order_id", "postgresql");

    private static WorkflowSweeper.Reassign reassign(String sql) {
        return new WorkflowSweeper.Reassign(
                Sql2WayParser.parse(sql, ExpressionFunctions.processDefault()), Map.of());
    }

    @Test
    void oneFailingTaskIsRolledBackToItsSavepointNamedAndSkippedWhileTheNextStillEscalates() {
        FakeJdbc jdbc = new FakeJdbc();
        // The poison task's resolver fails at execution (a data-dependent error the lint
        // cannot see); the healthy one, met second because it is due later, must still go.
        jdbc.poisonParameter = "P-1";
        List<java.util.logging.LogRecord> warnings = new ArrayList<>();
        java.util.logging.Handler handler = capture(warnings);
        java.util.logging.Logger logger = java.util.logging.Logger
                .getLogger(WorkflowSweeper.class.getName());
        logger.addHandler(handler);
        try {
            int escalated = sweeper(jdbc, List.of(new WorkflowSweeper.Rule("order", "review",
                    reassign("select fallback from resolvers where doc = /*docId*/'x'"),
                    null, null, DOCUMENT)), null, List.of(
                            new WorkflowTaskStore.Overdue("t-poison", "order", "P-1", "review",
                                    "alice", null),
                            new WorkflowTaskStore.Overdue("t-ok", "order", "G-1", "review",
                                    "carol", null)))
                    .sweep();

            assertThat(escalated).isEqualTo(1);
            assertThat(jdbc.calls.stream().filter("setSavepoint"::equals)).hasSize(2);
            assertThat(jdbc.calls.stream().filter(call -> call.startsWith("rollback("))).hasSize(1);
            assertThat(warnings)
                    .anyMatch(record -> record.getLevel() == java.util.logging.Level.WARNING
                            && String.valueOf(record.getMessage()).contains("skipped this sweep")
                            && List.of(record.getParameters()).contains("t-poison")
                            && List.of(record.getParameters()).contains("P-1"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void anEscalateCommandThatMatchesNoRowIsRefusedAndTheStateAdvanceRolledBack() {
        FakeJdbc jdbc = new FakeJdbc();
        // The column advance updates its row; the transition's own command matches nothing.
        jdbc.zeroRowsSql = "expired";
        List<java.util.logging.LogRecord> warnings = new ArrayList<>();
        java.util.logging.Handler handler = capture(warnings);
        java.util.logging.Logger logger = java.util.logging.Logger
                .getLogger(WorkflowSweeper.class.getName());
        logger.addHandler(handler);
        try {
            int escalated = sweeper(jdbc, List.of(new WorkflowSweeper.Rule("order", "review",
                    null,
                    new WorkflowSweeper.Escalate("expire", "expired",
                            Sql2WayParser.parse(
                                    "update orders set status = 'expired' where id = /*key*/'x'",
                                    ExpressionFunctions.processDefault()),
                            false, "orders", "status", "order_id"),
                    null, DOCUMENT)), null, List.of(
                            new WorkflowTaskStore.Overdue("t1", "order", "d1", "review", "alice",
                                    null)))
                    .sweep();

            assertThat(escalated).isZero();
            assertThat(jdbc.calls.stream().filter(call -> call.startsWith("rollback("))).hasSize(1);
            assertThat(warnings).anyMatch(record -> String.valueOf(record.getParameters()[4])
                    .contains("updated no rows"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static java.util.logging.Handler capture(List<java.util.logging.LogRecord> into) {
        return new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                into.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static WorkflowSweeper sweeper(FakeJdbc jdbc, WorkflowSweeper.Rule rule) {
        return sweeper(jdbc, rule, null, null);
    }

    private static WorkflowSweeper sweeper(FakeJdbc jdbc, WorkflowSweeper.Rule rule,
            io.tesseraql.core.outbox.OutboxStore outbox, String tenantId) {
        return sweeper(jdbc, List.of(rule), outbox, List.of(new WorkflowTaskStore.Overdue("t1",
                "order", "d1", "review", "alice", tenantId)));
    }

    private static WorkflowSweeper sweeper(FakeJdbc jdbc, List<WorkflowSweeper.Rule> rules,
            io.tesseraql.core.outbox.OutboxStore outbox, List<WorkflowTaskStore.Overdue> overdue) {
        WorkflowTaskStore taskStore = (WorkflowTaskStore) Proxy.newProxyInstance(
                WorkflowSweeperBoundTest.class.getClassLoader(),
                new Class<?>[]{WorkflowTaskStore.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "overdue" -> overdue;
                    default -> null;
                });
        return new WorkflowSweeper(rules, taskStore, null, outbox, "app",
                jdbc.dataSource(), null);
    }

    /**
     * A JDBC stack that records what was asked of it and answers one resolver row. A statement
     * bound with {@code poisonParameter} fails at execution; an update whose SQL contains
     * {@code zeroRowsSql} reports no row.
     */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private boolean rowRead;
        private String poisonParameter;
        private String zeroRowsSql;
        private String lastSql;
        private Object lastParameter;

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args)
                throws java.sql.SQLException {
            calls.add(method.getName() + (args == null || args.length == 0
                    ? ""
                    : "(" + args[0] + ")"));
            if (method.getName().equals("prepareStatement")) {
                lastSql = String.valueOf(args[0]);
            }
            if (method.getName().startsWith("set") && args != null && args.length >= 2) {
                lastParameter = args[1];
            }
            if ((method.getName().equals("executeQuery")
                    || method.getName().equals("executeUpdate"))
                    && poisonParameter != null && poisonParameter.equals(lastParameter)) {
                throw new java.sql.SQLException("invalid input for the resolver: " + lastParameter);
            }
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "prepareStatement" -> proxy(PreparedStatement.class);
                case "setSavepoint" -> proxy(java.sql.Savepoint.class);
                case "executeQuery" -> proxy(ResultSet.class);
                case "executeUpdate", "getUpdateCount" -> zeroRowsSql != null
                        && lastSql.contains(zeroRowsSql) ? 0 : 1;
                case "execute" -> Boolean.TRUE;
                case "next" -> nextRow();
                case "getString" -> "bob";
                case "toString" -> "fake";
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
