package io.tesseraql.yaml.model;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a command's {@code steps:} — an array whose items carry an {@code id:} plus the binding's
 * arm (docs/unified-sources.md decision 9):
 *
 * <pre>{@code
 * steps:
 *   - id: orderNo
 *     sequence: order-number
 *   - id: header
 *     sql: { file: insert-header.sql, mode: update, keys: [id] }
 * }</pre>
 *
 * <p>The surface's rule is that a namespace is a map and an ordered sequence is an array whose
 * items carry {@code id:}. Route {@code steps:} was the one map whose <em>authoring order</em>
 * was semantic — a reader had to know that a map here means something a map does not usually
 * mean. As an array the order is the syntax, and a command is literally the transactional
 * pipeline [jobs.md](../../../../../../../docs/jobs.md) has always called it.
 *
 * <p>The parsed shape stays an insertion-ordered map because a step is addressed by name
 * everywhere downstream ({@code steps.header.keys.id}); the array is how it is written, the map
 * is what it is.
 */
final class StepsDeserializer extends ValueDeserializer<Map<String, Binding>> {

    @Override
    public Map<String, Binding> deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = parser.readValueAsTree();
        Map<String, Binding> steps = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return steps;
        }
        if (!node.isArray()) {
            throw DatabindException.from(parser,
                    "steps: is a sequence of steps, each carrying an id: — write"
                            + " '- id: <name>' items, not a map of names");
        }
        for (JsonNode item : node) {
            JsonNode id = item.get("id");
            if (id == null || id.asString("").isBlank()) {
                throw DatabindException.from(parser,
                        "every step needs an id: — it is the name later steps and the response"
                                + " bind against (steps.<id>.*)");
            }
            String name = id.asString("");
            if (steps.containsKey(name)) {
                throw DatabindException.from(parser,
                        "duplicate step id '" + name + "' — a step id is a name, and two steps"
                                + " sharing one leaves a reference meaning either");
            }
            steps.put(name, context.readTreeAsValue(item, Binding.class));
        }
        return steps;
    }
}
