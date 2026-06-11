package io.tesseraql.operations.batch;

/** Batch job execution status (design ch. 26.4). The first milestone uses a core subset. */
public enum JobStatus {
    RUNNING, COMPLETED, FAILED, STOPPED
}
