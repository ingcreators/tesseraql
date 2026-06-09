package io.tesseraql.core.diag;

import java.util.List;

/**
 * Collects SQL executions in-process for operational diagnostics (design ch. 26.11). Implementations
 * decide which executions to keep (for example only those over a slow threshold) and how many.
 */
public interface SqlExecutionLog {

    /** Offers an execution sample to the log; the log may keep or drop it. */
    void record(SqlExecution execution);

    /** The retained samples, most recent first. */
    List<SqlExecution> recent();
}
