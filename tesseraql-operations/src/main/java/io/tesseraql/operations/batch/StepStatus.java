package io.tesseraql.operations.batch;

/** Batch step execution status (design ch. 26.4). */
public enum StepStatus {
    RUNNING, COMPLETED, FAILED,
    /** Recorded, not run: a rerun's {@code --from-failed-step} skips the source's completed steps. */
    SKIPPED,
    /** The cooperative stop took effect at a chunk-commit boundary; counts are kept. */
    STOPPED
}
