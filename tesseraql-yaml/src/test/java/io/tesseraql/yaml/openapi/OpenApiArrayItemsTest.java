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
                null, items, null, null, null, null);
    }

    @Test
    void theElementTypeAndEnumAreDescribed() {
        Map<String, Object> schema = OpenApiGenerator.fieldSchema(
                array(new InputField.InputItems("integer", List.of("1", "2"))));

        assertThat(schema).containsEntry("type", "array");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) schema.get("items");
        assertThat(items).containsEntry("type", "integer").containsEntry("enum", List.of("1", "2"));
    }

    @Test
    void anArrayWithoutItemsSaysOnlyThatItIsAnArray() {
        assertThat(OpenApiGenerator.fieldSchema(array(null)))
                .containsEntry("type", "array")
                .doesNotContainKey("items");
    }
}
