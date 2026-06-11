package io.tesseraql.core.threading;

import java.util.Objects;

/**
 * The policy for a named execution lane (design ch. 24): its carrier thread kind and the maximum
 * number of tasks allowed to run concurrently before backpressure is applied.
 *
 * <p>{@code maxConcurrency} bounds in-flight work regardless of lane type: for {@code VIRTUAL}
 * lanes it is the admission limit that gives backpressure to an otherwise unbounded virtual-thread
 * executor; for {@code PLATFORM} lanes it is the fixed pool size.
 *
 * @param name           the lane name referenced by routes (for example {@code io})
 * @param type           the carrier thread kind
 * @param maxConcurrency the maximum number of concurrently running tasks (must be at least 1)
 */
public record LanePolicy(String name, LaneType type, int maxConcurrency) {

    public LanePolicy {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1: " + maxConcurrency);
        }
    }

    /** A virtual-thread lane with the given admission limit. */
    public static LanePolicy virtual(String name, int maxConcurrency) {
        return new LanePolicy(name, LaneType.VIRTUAL, maxConcurrency);
    }

    /** A platform-thread lane backed by a fixed pool of the given size. */
    public static LanePolicy platform(String name, int maxConcurrency) {
        return new LanePolicy(name, LaneType.PLATFORM, maxConcurrency);
    }
}
