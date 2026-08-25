package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * The {@code use:} arm of a step (docs/transactional-writes.md, "Shared step fragments"): this
 * step is a named fragment's sequence, expanded here at manifest load.
 *
 * <p>It is an arm like {@code sql:} or {@code http:} — the step still carries its own
 * {@code id:}, which becomes the prefix its expanded steps are named under, and the wiring lives
 * inside the arm where every other mechanism's wiring lives. The gap analysis sketched
 * {@code use:}/{@code as:} as step-level keys; nesting them keeps the surface's own grammar,
 * where an ordered sequence is an array whose items carry {@code id:} and {@code params:}
 * belongs to the arm.
 *
 * @param fragment the declared fragment's name
 * @param params   the reference's wiring: each of the fragment's declared binds to the bindable
 *                 path or literal supplying it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FragmentUse(String fragment, Map<String, String> params) {

    public FragmentUse {
        params = params == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params));
    }
}
