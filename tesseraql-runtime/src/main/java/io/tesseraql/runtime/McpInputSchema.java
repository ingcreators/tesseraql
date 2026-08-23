package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.yaml.model.InputField;
import java.util.Map;

/**
 * Derives an MCP tool's JSON-Schema {@code inputSchema} from a route's declared {@code input:}
 * constraints (roadmap Phase 24 follow-on), so the connecting model sees the same types, required
 * fields, ranges, and enums the runtime validates. The validation itself still happens server-side
 * in the route's input binder; the schema just guides the client toward valid arguments.
 */
final class McpInputSchema {

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    private McpInputSchema() {
    }

    static ObjectNode fromInputs(Map<String, InputField> inputs) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        inputs.forEach((name, field) -> {
            ObjectNode property = properties.putObject(name);
            property.put("type", jsonType(field.type()));
            // What the argument is, in the author's words. JSON Schema's own key, and the hint a
            // model reads when it decides what to send: a declared description that stopped at
            // the manifest left the model guessing from the field name alone.
            if (field.description() != null && !field.description().isBlank()) {
                property.put("description", field.description());
            }
            if ("date".equals(field.type())) {
                property.put("format", "date");
            } else if ("datetime".equals(field.type())) {
                property.put("format", "date-time");
            }
            if (field.enumValues() != null && !field.enumValues().isEmpty()) {
                ArrayNode values = property.putArray("enum");
                field.enumValues().forEach(values::add);
            }
            // The element shape, now that elements are validated against it: a model that sees
            // the constraint produces a valid call, and the alternative is a rejection it has no
            // way to diagnose.
            if ("array".equals(field.type()) && field.items() != null) {
                ObjectNode items = property.putObject("items");
                items.put("type", jsonType(field.items().type()));
                if (!field.items().enumValues().isEmpty()) {
                    ArrayNode elementValues = items.putArray("enum");
                    field.items().enumValues().forEach(elementValues::add);
                }
            }
            if (field.min() != null) {
                property.put("minimum", field.min());
            }
            if (field.max() != null) {
                property.put("maximum", field.max());
            }
            if (field.maxLength() != null) {
                property.put("maxLength", field.maxLength());
            }
            if (field.required()) {
                required.add(name);
            }
        });
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private static String jsonType(String inputType) {
        return switch (inputType == null ? "string" : inputType) {
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            // A declared array fell through to "string", so a model was told to send text where
            // the framework rejects anything but a list — a tool call the model cannot diagnose,
            // since the schema it was given is what it followed.
            case "array" -> "array";
            default -> "string";
        };
    }
}
