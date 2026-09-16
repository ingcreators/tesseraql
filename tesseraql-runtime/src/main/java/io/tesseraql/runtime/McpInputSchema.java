package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.yaml.model.InputField;
import java.util.Map;

/**
 * Derives an MCP tool's JSON-Schema {@code inputSchema} from a route's declared {@code input:}
 * constraints (roadmap Phase 24 follow-on), so the connecting model sees the same types, required
 * fields, ranges, lengths, patterns, formats and enums the runtime validates. The validation itself
 * still happens server-side in the route's input binder; the schema just guides the client toward
 * valid arguments.
 *
 * <p>One description per field, at the top level and inside an object element alike: the two
 * emitters were copies, and the string constraints Phase 40 added ({@code pattern},
 * {@code minLength}, the email/uuid/url formats) reached the OpenAPI contract and the binder but
 * neither copy here — a model told only {@code string} sent a SKU the binder refused
 * (docs/audit-low-leads.md, G4).
 */
final class McpInputSchema {

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    private McpInputSchema() {
    }

    static ObjectNode fromInputs(Map<String, InputField> inputs) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        describeFields(schema, inputs);
        return schema;
    }

    /** The element shape an object array declares ({@code items.fields:}), or its scalar shape. */
    private static void describeElements(ObjectNode items, InputField.InputItems declared) {
        if (!declared.hasFields()) {
            items.put("type", jsonType(declared.type()));
            if (!declared.enumValues().isEmpty()) {
                ArrayNode elementValues = items.putArray("enum");
                declared.enumValues().forEach(elementValues::add);
            }
            return;
        }
        items.put("type", "object");
        describeFields(items, declared.fields());
    }

    /** The {@code properties} and {@code required} of an object, field by field. */
    private static void describeFields(ObjectNode object, Map<String, InputField> fields) {
        ObjectNode properties = object.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        fields.forEach((name, field) -> {
            describe(properties.putObject(name), field);
            if (field.required()) {
                required.add(name);
            }
        });
        if (!required.isEmpty()) {
            object.set("required", required);
        }
    }

    /**
     * One field's schema: the type, the author's description, the date formats, the semantic
     * string formats ({@code url} as JSON Schema's {@code uri}, the OpenAPI emitter's mapping),
     * the enum, the element shape, and every bound the binder enforces. A constraint the model
     * cannot see is a rejection it has no way to diagnose.
     */
    private static void describe(ObjectNode property, InputField field) {
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
        } else if (field.hasStringFormat()) {
            property.put("format", "url".equals(field.format()) ? "uri" : field.format());
        }
        if (field.enumValues() != null && !field.enumValues().isEmpty()) {
            ArrayNode values = property.putArray("enum");
            field.enumValues().forEach(values::add);
        }
        // The element shape, now that elements are validated against it: a model that sees
        // the constraint produces a valid call, and the alternative is a rejection it has no
        // way to diagnose.
        if ("array".equals(field.type()) && field.items() != null) {
            describeElements(property.putObject("items"), field.items());
        }
        if (field.pattern() != null) {
            property.put("pattern", field.pattern());
        }
        if (field.minLength() != null) {
            property.put("minLength", field.minLength());
        }
        if (field.maxLength() != null) {
            property.put("maxLength", field.maxLength());
        }
        if (field.min() != null) {
            property.put("minimum", field.min());
        }
        if (field.max() != null) {
            property.put("maximum", field.max());
        }
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
