package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route response declaration (design ch. 6.3, 6.4). Only the JSON response shape is modelled in
 * the first milestone; HTML and streaming responses are added in later phases.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseSpec(JsonResponse json) {

    /**
     * A JSON response. {@code body} is a free-form tree (maps, lists, scalars) whose leaf strings
     * are source expressions such as {@code sql.rows}, {@code sql.rowCount}, or {@code params.limit}
     * resolved by the response renderer against the execution context.
     *
     * @param status HTTP status code, defaulting to 200
     * @param body   the response body template
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonResponse(Integer status, Object body) {

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }
    }
}
