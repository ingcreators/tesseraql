package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A role-typed SQL reference: a colocated file, and the binds it needs.
 *
 * <p>{@code validate.file}, scope arms, {@code rules/} entries and a workflow transition's
 * {@code command:} are not bindings — each has its own contract (violation rows, a boolean
 * predicate, assignee rows, a state-advancing write), so they stay outside the binding union
 * deliberately. What they share is a spelling: {@code { file:, params: }}
 * (docs/unified-sources.md decision 14). A bare string was the surface's one exception, and it
 * left the statement's binds a level out from the statement.
 *
 * @param file   the {@code .sql} file, relative to the declaring document
 * @param params bind name to a source expression over the execution context
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlRef(String file, Map<String, String> params) {

    public SqlRef {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
