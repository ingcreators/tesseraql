package io.tesseraql.core.diag;

import java.util.List;

/** A {@link SqlExecutionLog} that records nothing, used when diagnostics are disabled. */
public final class NoopSqlExecutionLog implements SqlExecutionLog {

    public static final NoopSqlExecutionLog INSTANCE = new NoopSqlExecutionLog();

    private NoopSqlExecutionLog() {
    }

    @Override
    public void record(SqlExecution execution) {
        // no-op
    }

    @Override
    public List<SqlExecution> recent() {
        return List.of();
    }
}
