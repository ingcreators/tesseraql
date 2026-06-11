package io.tesseraql.runtime;

import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.LanePolicy;
import io.tesseraql.core.threading.LaneType;
import io.tesseraql.yaml.config.AppConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link ExecutionLanes} from the {@code threading.lanes} configuration block (design ch. 24).
 *
 * <p>Example:
 * <pre>
 * threading:
 *   lanes:
 *     io:   { type: virtual,  maxConcurrency: 256 }
 *     cpu:  { type: platform, maxConcurrency: 8 }
 * </pre>
 * Apps without the block get an empty set of lanes, so lane dispatch is opt-in.
 */
final class LaneConfigs {

    private static final int DEFAULT_MAX_CONCURRENCY = 64;

    private LaneConfigs() {
    }

    static ExecutionLanes load(AppConfig config) {
        Object node = config.navigate("threading.lanes");
        if (!(node instanceof Map<?, ?> lanes) || lanes.isEmpty()) {
            return ExecutionLanes.empty();
        }
        List<LanePolicy> policies = new ArrayList<>();
        for (Map.Entry<?, ?> entry : lanes.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Map<?, ?> lane = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
            LaneType type = "platform".equalsIgnoreCase(string(lane.get("type")))
                    ? LaneType.PLATFORM
                    : LaneType.VIRTUAL;
            policies.add(new LanePolicy(name, type, maxConcurrency(lane.get("maxConcurrency"))));
        }
        return ExecutionLanes.of(policies);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int maxConcurrency(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            return Integer.parseInt(String.valueOf(value).trim());
        }
        return DEFAULT_MAX_CONCURRENCY;
    }
}
