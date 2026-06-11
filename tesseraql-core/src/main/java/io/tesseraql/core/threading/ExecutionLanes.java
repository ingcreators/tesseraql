package io.tesseraql.core.threading;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of named execution lanes available to a running application (design ch. 24).
 *
 * <p>Built once at startup from the configured {@link LanePolicy} list, bound into the runtime, and
 * closed at shutdown. Lanes are looked up by name when a route declares one.
 */
public final class ExecutionLanes implements AutoCloseable {

    private static final TqlErrorCode UNKNOWN_LANE = new TqlErrorCode(TqlDomain.LANE, 5004);

    private final Map<String, Lane> lanes;

    private ExecutionLanes(Map<String, Lane> lanes) {
        this.lanes = lanes;
    }

    /** Builds the lanes from their policies; duplicate names are rejected. */
    public static ExecutionLanes of(List<LanePolicy> policies) {
        Map<String, Lane> built = new LinkedHashMap<>();
        for (LanePolicy policy : policies) {
            if (built.putIfAbsent(policy.name(), new Lane(policy)) != null) {
                throw new IllegalArgumentException("Duplicate lane name: " + policy.name());
            }
        }
        return new ExecutionLanes(Map.copyOf(built));
    }

    /** An empty set of lanes (no lane-based dispatch configured). */
    public static ExecutionLanes empty() {
        return new ExecutionLanes(Map.of());
    }

    /** Returns the lane with the given name, or throws {@code TQL-LANE-5004} if it is unknown. */
    public Lane lane(String name) {
        Lane lane = lanes.get(name);
        if (lane == null) {
            throw new TqlException(UNKNOWN_LANE, "Unknown execution lane: " + name);
        }
        return lane;
    }

    public boolean has(String name) {
        return lanes.containsKey(name);
    }

    public Collection<Lane> all() {
        return lanes.values();
    }

    @Override
    public void close() {
        lanes.values().forEach(Lane::close);
    }
}
