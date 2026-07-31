package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A one-action dispatch (docs/workflow-expressiveness.md slice 3): a named endpoint that
 * tries its member transitions in declaration order and fires the first whose state and
 * guard hold — the client calls one action, the state machine picks the lane. A member
 * refusing with a wrong-state or guard code falls through to the next; any other outcome
 * (success, 403, row-authority 409) is the dispatch's outcome. No member holding is a
 * {@code 422} naming the attempted transitions.
 *
 * @param id    the action name; the endpoint is {@code POST {basePath}/{key}/<id>}
 * @param oneOf the member transition ids, tried in order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchSpec(String id, List<String> oneOf) {

    public DispatchSpec {
        oneOf = oneOf == null ? List.of() : List.copyOf(oneOf);
    }
}
