package io.tesseraql.yaml.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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
final class StepsDeserializer extends JsonDeserializer<Map<String, Binding>> {

    @Override
    public Map<String, Binding> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.readValueAsTree();
        Map<String, Binding> steps = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return steps;
        }
        if (!node.isArray()) {
            throw new com.fasterxml.jackson.databind.JsonMappingException(parser,
                    "steps: is a sequence of steps, each carrying an id: — write"
                            + " '- id: <name>' items, not a map of names");
        }
        for (JsonNode item : node) {
            JsonNode id = item.get("id");
            if (id == null || id.asText().isBlank()) {
                throw new com.fasterxml.jackson.databind.JsonMappingException(parser,
                        "every step needs an id: — it is the name later steps and the response"
                                + " bind against (steps.<id>.*)");
            }
            String name = id.asText();
            if (steps.containsKey(name)) {
                throw new com.fasterxml.jackson.databind.JsonMappingException(parser,
                        "duplicate step id '" + name + "' — a step id is a name, and two steps"
                                + " sharing one leaves a reference meaning either");
            }
            steps.put(name, parser.getCodec().treeToValue(item, Binding.class));
        }
        return steps;
    }
}
