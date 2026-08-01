package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A restartable chunk step (docs/batch-platform.md track C): the reader streams a
 * keyset-ordered SELECT on its own connection, the writer runs once per row on a second
 * connection that commits every {@link #effectiveCommitEvery()} rows, and the last handled
 * key of each committed chunk is checkpointed so a rerun for the same business date resumes
 * where the failure stopped.
 *
 * @param reader  the streaming SELECT; its contract is keyset pagination — filter on the
 *                {@code chunk.after} bind under a 2-way {@code if} guard so a fresh run
 *                (which binds no checkpoint) reads from the top, and order by the key
 * @param writer  the per-row statement; binds the reader's row as {@code row.<column>} plus
 *                the ambient {@code batch.*}/{@code job.*} context
 * @param key     the reader column checkpoints track (default {@code id}); its values must be
 *                unique and ascending under the reader's {@code order by}
 * @param commitEvery rows per writer transaction (default 500)
 * @param onError the skip policy; absent means fail-fast ({@code skipLimit: 0})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkSpec(SqlBinding reader, SqlBinding writer, String key, Integer commitEvery,
        OnError onError) {

    /** The reader column checkpoints track. */
    public String effectiveKey() {
        return key == null || key.isBlank() ? "id" : key;
    }

    /** Rows per writer transaction. */
    public int effectiveCommitEvery() {
        return commitEvery == null ? 500 : commitEvery;
    }

    /** Writer failures tolerated before the step fails; 0 keeps fail-fast. */
    public int effectiveSkipLimit() {
        return onError == null || onError.skipLimit() == null ? 0 : onError.skipLimit();
    }

    /**
     * The skip policy: a writer failure on one row is recorded in {@code tql_job_skips} and
     * processing continues — until the limit is exceeded, which fails the step.
     *
     * @param skipLimit tolerated writer failures (default 0)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OnError(Integer skipLimit) {
    }
}
