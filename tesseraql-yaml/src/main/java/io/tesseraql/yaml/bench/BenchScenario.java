package io.tesseraql.yaml.bench;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A load scenario — {@code bench/<name>.yml}, {@code kind: bench} (docs/deployment-maturity.md
 * decision 8): the requests {@code tesseraql bench} drives, weighted, and the shape of the run.
 *
 * <p>A document of its own kind so it is lint-checked like a suite: an unknown route, a param
 * the route does not declare and a write without {@code writes: allowed} are errors before a
 * request is sent, and an editor completes it from its schema. Writes are refused unless the
 * scenario says {@code writes: allowed}, because a load run that mutates data is a decision the
 * author writes down, never a flag typed in a hurry.
 *
 * @param version     always {@code tesseraql/v1}
 * @param kind        always {@code bench}
 * @param description what the scenario measures, for the report
 * @param concurrency workers in the closed loop; with {@code rate}, the bound on requests in
 *                    flight (10 when absent)
 * @param duration    how long the run lasts, e.g. {@code 30s} (30 seconds when absent)
 * @param rampUp      the span the workers start across, e.g. {@code 5s}; none when absent
 * @param rate        open loop: requests per second offered instead of the closed loop
 * @param writes      {@code allowed} before a request may drive a route that is not GET or HEAD
 * @param requests    the requests, chosen by weight
 */
public record BenchScenario(String version, String kind, String description, Integer concurrency,
        String duration, @JsonProperty("rampUp") String rampUp, Double rate, String writes,
        List<Request> requests) {

    /** The document kind. */
    public static final String KIND = "bench";

    /** TQL-YAML-1413: a bench scenario without its version, its kind or any request. */
    public static final TqlErrorCode MALFORMED = new TqlErrorCode(TqlDomain.YAML, 1413);

    /** The closed loop's worker count when the scenario and the command line say nothing. */
    public static final int DEFAULT_CONCURRENCY = 10;

    /** The run's length when the scenario and the command line say nothing. */
    public static final String DEFAULT_DURATION = "30s";

    /**
     * One request shape.
     *
     * @param route  the route id, as {@code tesseraql routes} lists it
     * @param params the route's declared inputs: path placeholders first, the rest the query
     * @param body   the JSON body a write sends, when the route takes one
     * @param weight how often this request is chosen relative to the others (1 when absent)
     */
    public record Request(String route, Map<String, Object> params, Object body, Integer weight) {

        public Request {
            params = params == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        /** The weight, one when the scenario says nothing. */
        public int weightOrOne() {
            return weight == null || weight < 1 ? 1 : weight;
        }
    }

    public BenchScenario {
        requests = requests == null ? List.of() : List.copyOf(requests);
    }

    /** Whether the scenario lets a request drive a route that is not GET or HEAD. */
    public boolean writesAllowed() {
        return "allowed".equals(writes);
    }
}
