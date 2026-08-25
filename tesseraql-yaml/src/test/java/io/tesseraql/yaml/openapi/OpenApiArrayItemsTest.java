package io.tesseraql.yaml.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.InputField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An array input's element shape reaches the published document.
 *
 * <p>The generator emitted {@code type: array} and stopped, so the document described a looser
 * contract than a caller has to satisfy — elements are validated against {@code items:} now, and
 * a caller who follows the document still gets rejected. That is the direction of error that
 * costs someone a request they had no way to anticipate.
 */
class OpenApiArrayItemsTest {

    private static InputField array(InputField.InputItems items) {
        return new InputField("array", false, null, null, null, null, null, null, null, null,
                null, items, null, null, null, null, null, null, null, null);
    }

    @Test
    void theElementTypeAndEnumAreDescribed() {
        Map<String, Object> schema = OpenApiGenerator.fieldSchema(
                array(new InputField.InputItems("integer", List.of("1", "2"), null)));

        assertThat(schema).containsEntry("type", "array");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) schema.get("items");
        assertThat(items).containsEntry("type", "integer").containsEntry("enum", List.of("1", "2"));
    }

    /**
     * An object array publishes the line a caller has to send: the properties, their
     * constraints, and which of them are required (docs/declarative-validation.md, "Line items").
     */
    @Test
    void anObjectElementPublishesItsFieldContract() {
        java.util.Map<String, InputField> fields = new java.util.LinkedHashMap<>();
        fields.put("itemId", new InputField("string", true, null, null, null, 40, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));
        fields.put("qty", new InputField("integer", true, null, java.math.BigDecimal.ONE, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null));
        fields.put("note", new InputField("string", false, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

        Map<String, Object> schema = OpenApiGenerator.fieldSchema(
                array(new InputField.InputItems(null, null, fields)));

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) schema.get("items");
        assertThat(items).containsEntry("type", "object")
                .containsEntry("required", List.of("itemId", "qty"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) items.get("properties");
        assertThat(properties).containsOnlyKeys("itemId", "qty", "note");
        @SuppressWarnings("unchecked")
        Map<String, Object> qty = (Map<String, Object>) properties.get("qty");
        assertThat(qty).containsEntry("type", "integer")
                .containsEntry("minimum", java.math.BigDecimal.ONE);
    }

    @Test
    void anArrayWithoutItemsSaysOnlyThatItIsAnArray() {
        assertThat(OpenApiGenerator.fieldSchema(array(null)))
                .containsEntry("type", "array")
                .doesNotContainKey("items");
    }

    /**
     * A field's {@code description:} is JSON Schema's own key, so it rides into the published
     * contract the way it rides into an MCP tool's input schema — one key, every surface derived
     * from {@code input:}.
     */
    @Test
    void aFieldDescriptionRidesIntoTheContract() {
        InputField sku = new InputField("string", false, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                "The stock keeping unit.");

        assertThat(OpenApiGenerator.fieldSchema(sku))
                .containsEntry("description", "The stock keeping unit.");
        assertThat(OpenApiGenerator.fieldSchema(array(null))).doesNotContainKey("description");
    }
}
