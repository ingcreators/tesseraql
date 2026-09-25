package io.tesseraql.pipeline.tenant;

/**
 * What a piece of work borrows a connection for, where {@code main} may keep a second pool onto its
 * own database for it (docs/capacity-defaults.md decision 5).
 *
 * <p>A role pool is part of {@code main}, not a named datasource: tenant routing, which replaces
 * {@code main}, carries it with it, so in a per-tenant mode each tenant's pool has the same roles
 * (decision 5a). Which work takes which role is decided by the executor, never by who started it.
 */
public enum PoolRole {

    /** Requests, and everything that is neither of the others: the pool itself. */
    ONLINE,

    /** Every execution of a {@code kind: job}, a poll-triggered import included. */
    JOBS,

    /** The asynchronous transfers a {@code file-export} or {@code file-import} route starts. */
    FILE_TRANSFERS
}
