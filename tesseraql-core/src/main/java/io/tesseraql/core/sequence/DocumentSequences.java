package io.tesseraql.core.sequence;

import java.sql.Connection;

/**
 * Allocates document numbers from managed, gapless sequences (roadmap Phase 18).
 *
 * <p>Allocation runs on a caller-supplied connection so the number rides the command's
 * transaction: the allocating {@code UPDATE} takes the sequence row's lock, serializing
 * concurrent allocations until the transaction ends, and a rollback returns the number —
 * the gapless option with row-lock semantics. Sequences are created on first use.
 */
public interface DocumentSequences {

    /** Allocates the next value of the named sequence on the given (transactional) connection. */
    long next(Connection connection, String name);
}
