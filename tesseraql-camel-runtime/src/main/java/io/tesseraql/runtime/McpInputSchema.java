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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            if ("date".equals(field.type())) {
                property.put("format", "date");
            } else if ("datetime".equals(field.type())) {
                property.put("format", "date-time");
            }
            if (field.enumValues() != null && !field.enumValues().isEmpty()) {
                ArrayNode values = property.putArray("enum");
                field.enumValues().forEach(values::add);
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
            default -> "string";
        };
    }
}
