package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;

/**
 * A transition's guard (docs/approval-workflow.md, docs/workflow-expressiveness.md): either
 * the whitelist expression over {@code document.*}/{@code task.*}/{@code principal.*}/
 * {@code decision.*} paths, or a 2-way SQL <b>query</b> file evaluated in the transition's
 * transaction — rows mean pass, no rows fails the transition with the declared {@code code}
 * riding the 422 payload. The expression form remains the tool for column checks; the file
 * form exists for set conditions ("every line is priced", "a shipment is registered") that
 * previously forced denormalized counters or exists-in-WHERE commands failing as a generic
 * conflict.
 *
 * <p>In YAML a bare string is the expression; a map ({@code file:}, optional {@code code:}
 * and {@code message:}) is the SQL form. Declaring both, or a map with neither, is
 * {@code TQL-WORKFLOW-3108}.
 *
 * @param expression the whitelist boolean expression, or {@code null} in the file form
 * @param file       the 2-way SQL query file (relative to the workflow document), or
 *                   {@code null} in the expression form
 * @param code       the app-level refusal code carried in the 422 payload's details
 *                   (default {@code guard-failed})
 * @param message    an optional {@code messages/} catalog key carried beside the code
 */
public record GuardSpec(String expression, String file, String code, String message) {

    /** A bare YAML string is the expression form; a map is the SQL-file form. */
    @JsonCreator
    public static GuardSpec of(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String expression) {
            return new GuardSpec(expression, null, null, null);
        }
        if (value instanceof Map<?, ?> map) {
            return new GuardSpec(text(map, "expression"), text(map, "file"),
                    text(map, "code"), text(map, "message"));
        }
        throw new IllegalArgumentException(
                "guard must be an expression string or a {file, code, message} map");
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
