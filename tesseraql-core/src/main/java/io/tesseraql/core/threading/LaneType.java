package io.tesseraql.core.threading;

/**
 * The kind of carrier thread an execution lane uses (design ch. 24).
 *
 * <p>{@code VIRTUAL} lanes run each task on a JDK virtual thread, which is the default for
 * blocking I/O work (SQL, HTTP). {@code PLATFORM} lanes run on a fixed pool of platform threads,
 * for CPU-bound or pinning-prone work that should not be multiplexed onto carriers.
 */
public enum LaneType {
    VIRTUAL, PLATFORM
}
