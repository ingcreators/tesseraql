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
                null, items, null, null, null, null, null, null, null, null, null);
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
                array(new InputField.InputItems("string", List.of("A", "B"), null))));

        ObjectNode items = (ObjectNode) schema.path("properties").path("codes").path("items");
        assertThat(items.path("type").asText()).isEqualTo("string");
        assertThat(items.path("enum").toString()).isEqualTo("[\"A\",\"B\"]");
    }

    /**
     * A model told only "array" sends lines the binder refuses field by field, and it has no way
     * to diagnose a rejection against a schema that never mentioned the fields.
     */
    @Test
    void anObjectElementTravelsAsItsFieldContract() {
        java.util.Map<String, InputField> fields = new java.util.LinkedHashMap<>();
        fields.put("itemId", new InputField("string", true, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null));
        fields.put("qty", new InputField("integer", true, null, java.math.BigDecimal.ONE, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null));
        fields.put("desiredDate", new InputField("date", false, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

        ObjectNode schema = McpInputSchema.fromInputs(Map.of("lines",
                array(new InputField.InputItems(null, null, fields))));

        ObjectNode items = (ObjectNode) schema.path("properties").path("lines").path("items");
        assertThat(items.path("type").asText()).isEqualTo("object");
        assertThat(items.path("required").toString()).isEqualTo("[\"itemId\",\"qty\"]");
        assertThat(items.path("properties").path("qty").path("minimum").asInt()).isEqualTo(1);
        assertThat(items.path("properties").path("desiredDate").path("format").asText())
                .isEqualTo("date");
    }

    /**
     * {@code description:} is JSON Schema's own key and the hint a model reads when it chooses a
     * value, so a field that declares one says so on the wire rather than at the manifest.
     */
    @Test
    void aFieldDescriptionIsTheSchemaDescription() {
        InputField sku = new InputField("string", true, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "The stock keeping unit to look up.");

        ObjectNode schema = McpInputSchema.fromInputs(Map.of("sku", sku));

        assertThat(schema.path("properties").path("sku").path("description").asText())
                .isEqualTo("The stock keeping unit to look up.");
    }

    /** A field with no description carries no key, rather than a null or an empty string. */
    @Test
    void aFieldWithoutOneCarriesNoDescription() {
        ObjectNode schema = McpInputSchema.fromInputs(Map.of("ids", array(null)));

        assertThat(schema.path("properties").path("ids").has("description")).isFalse();
    }
}
