package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.yaml.model.InputField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An MCP tool's input schema describes what the framework actually accepts.
 *
 * <p>A declared {@code type: array} fell through to {@code "string"}, so a model was told to send
 * text where the framework rejects anything but a list — a tool call the model cannot diagnose,
 * because the schema it was given is what it followed. Elements are validated against
 * {@code items:} now, so the schema has to carry that too: a model that sees the constraint
 * produces a valid call.
 */
class McpInputSchemaTest {

    private static InputField array(InputField.InputItems items) {
        return new InputField("array", false, null, null, null, null, null, null, null, null,
                null, items, null, null, null, null);
    }

    @Test
    void aDeclaredArrayIsAnArray() {
        ObjectNode schema = McpInputSchema.fromInputs(Map.of("ids", array(null)));

        assertThat(schema.path("properties").path("ids").path("type").asText())
                .isEqualTo("array");
    }

    @Test
    void theElementTypeAndEnumTravelWithIt() {
        ObjectNode schema = McpInputSchema.fromInputs(Map.of("codes",
                array(new InputField.InputItems("string", List.of("A", "B")))));

        ObjectNode items = (ObjectNode) schema.path("properties").path("codes").path("items");
        assertThat(items.path("type").asText()).isEqualTo("string");
        assertThat(items.path("enum").toString()).isEqualTo("[\"A\",\"B\"]");
    }
}
