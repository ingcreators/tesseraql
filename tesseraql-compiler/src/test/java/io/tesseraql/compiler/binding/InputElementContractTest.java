package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.InputPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The line-item input contract ({@code items.fields:}, docs/declarative-validation.md).
 *
 * <p>The deny-by-default posture {@code input:} holds — unknown fields rejected, types coerced,
 * bounds enforced, violations reported against a field — silently did not apply inside the one
 * place a business form carries most of its data.
 */
class InputElementContractTest {

    private static InputField element(String type, boolean required, BigDecimal min,
            String pattern, String requiredWhen) {
        return new InputField(type, required, null, min, null, null, null, null, null, null,
                null, null, pattern, null, requiredWhen, null, null, null, null, null, null);
    }

    private static InputField lines(Map<String, InputField> fields) {
        return new InputField("array", true, null, null, null, null, null, null, null, null,
                null, new InputField.InputItems(null, null, fields), null, null, null, null, null,
                null, null, null, null);
    }

    private static Map<String, Object> bind(Map<String, InputField> fields, Object elements) {
        return bind(fields, elements, InputPolicy.defaults());
    }

    private static Map<String, Object> bind(Map<String, InputField> fields, Object elements,
            InputPolicy policy) {
        return InputBinder.bind(Map.of("lines", lines(fields)), name -> null,
                name -> elements, Locale.ENGLISH, null,
                new InputBinder.ElementRules(policy,
                        io.tesseraql.core.expr.ExpressionFunctions.builtInsOnly()));
    }

    private static Map<String, InputField> contract() {
        Map<String, InputField> fields = new LinkedHashMap<>();
        fields.put("itemId", element("string", true, null, "[A-Z]{2}-[0-9]{3}", null));
        fields.put("qty", element("integer", true, BigDecimal.ONE, null, null));
        fields.put("desiredDate", element("date", false, null, null, null));
        return fields;
    }

    @Test
    void everyElementFieldIsCoercedToItsDeclaredType() {
        Map<String, Object> bound = bind(contract(), List.of(
                Map.of("itemId", "IT-001", "qty", "3", "desiredDate", "2026-09-01")));

        List<?> rows = (List<?>) bound.get("lines");
        assertThat(rows).hasSize(1);
        assertThat(row(rows, 0))
                .containsEntry("itemId", "IT-001")
                .containsEntry("qty", 3L)
                .containsEntry("desiredDate", LocalDate.of(2026, 9, 1));
    }

    /** The point of the whole contract: a bad cell says which cell it is. */
    @Test
    void aViolationAddressesItselfByIndexAndField() {
        assertThatThrownBy(() -> bind(contract(), List.of(
                Map.of("itemId", "IT-001", "qty", "1"),
                Map.of("itemId", "IT-002", "qty", "1"),
                Map.of("itemId", "IT-003", "qty", "0"))))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("field", "lines[2].qty")
                        .containsEntry("code", "min"));
    }

    @Test
    void aMissingRequiredElementFieldIsRejected() {
        assertThatThrownBy(() -> bind(contract(), List.of(Map.of("qty", "1"))))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("field", "lines[0].itemId")
                        .containsEntry("code", "required"));
    }

    @Test
    void anElementFieldOutsideItsPatternIsRejected() {
        assertThatThrownBy(() -> bind(contract(), List.of(
                Map.of("itemId", "nope", "qty", "1"))))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("field", "lines[0].itemId")
                        .containsEntry("code", "pattern"));
    }

    /** The mass-assignment guard, one level down — deny by default. */
    @Test
    void anUndeclaredElementFieldIsRejected() {
        assertThatThrownBy(() -> bind(contract(), List.of(
                Map.of("itemId", "IT-001", "qty", "1", "unitPrice", "9.99"))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("lines[0].unitPrice");
    }

    @Test
    void anUndeclaredElementFieldFollowsTheRoutePolicy() {
        Map<String, Object> bound = bind(contract(), List.of(
                Map.of("itemId", "IT-001", "qty", "1", "unitPrice", "9.99")),
                new InputPolicy("ignore", "reject"));

        assertThat(row((List<?>) bound.get("lines"), 0))
                .containsOnlyKeys("itemId", "qty");
    }

    @Test
    void anElementThatIsNotAnObjectIsRejected() {
        assertThatThrownBy(() -> bind(contract(), List.of("IT-001")))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("field", "lines[0]")
                        .containsEntry("code", "type"));
    }

    /** An element's requiredWhen sees its own element, not the request around it. */
    @Test
    void anElementConditionSeesTheElementAndItsIndex() {
        Map<String, InputField> fields = contract();
        fields.put("note", element("string", false, null, null, "item.qty > 100"));

        assertThat(bind(fields, List.of(Map.of("itemId", "IT-001", "qty", "2")))).isNotEmpty();

        assertThatThrownBy(() -> bind(fields, List.of(
                Map.of("itemId", "IT-001", "qty", "2"),
                Map.of("itemId", "IT-002", "qty", "500"))))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("field", "lines[1].note")
                        .containsEntry("code", "required"));
    }

    @Test
    void anElementConditionCanReadItsPosition() {
        Map<String, InputField> fields = contract();
        fields.put("note", element("string", false, null, null, "item_index > 0"));

        assertThatThrownBy(() -> bind(fields, List.of(
                Map.of("itemId", "IT-001", "qty", "1"),
                Map.of("itemId", "IT-002", "qty", "1"))))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("field", "lines[1].note"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(List<?> rows, int index) {
        return (Map<String, Object>) rows.get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstField(TqlException ex) {
        return (Map<String, Object>) ((List<?>) ex.details().get("fields")).get(0);
    }
}
