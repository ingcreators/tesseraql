package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A one-action dispatch (docs/workflow-expressiveness.md slice 3, refined in
 * docs/transition-engine.md): a named endpoint that tries its member transitions in
 * declaration order and fires the first whose state and guard hold — the client calls
 * one action, the state machine picks the lane. A member refusing with a wrong-state or
 * guard code falls through to the next; any other outcome (success, 403, row-authority
 * 409) is the dispatch's outcome. No member holding is a {@code 422} naming the
 * attempted transitions and each one's refusal.
 *
 * @param id     the action name; the endpoint is {@code POST {basePath}/{key}/<id>}
 * @param decide decisions evaluated once, before the member loop, after the document
 *               binds — a member that declares no {@code decide:} of its own inherits
 *               the results as {@code decision.*} (docs/transition-engine.md track B); a
 *               member alias colliding with a dispatch alias is a lint error
 * @param oneOf  the member transition ids, tried in order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchSpec(String id, Map<String, DecisionUse> decide, List<String> oneOf) {

    public DispatchSpec {
        decide = decide == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(decide));
        oneOf = oneOf == null ? List.of() : List.copyOf(oneOf);
    }
}
