package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class InputBinderTest {

    private static InputField field(String type, String format) {
        return new InputField(type, true, null, null, null, null, null, null, null, null,
                format, null);
    }

    private static Function<String, String> value(String raw) {
        return name -> raw;
    }

    @Test
    void datesParseWithTheDeclaredPatternAndLocale() {
        Map<String, Object> bound = InputBinder.bind(
                Map.of("orderDate", field("date", "yyyy/MM/dd")),
                value("2026/06/12"), Locale.forLanguageTag("ja"));

        assertThat(bound.get("orderDate")).isEqualTo(LocalDate.of(2026, 6, 12));
    }

    @Test
    void numbersParseLocaleAwareGroupingAndDecimalSeparators() {
        Map<String, Object> bound = InputBinder.bind(
                Map.of("amount", field("number", "#,##0.##")),
                value("1.234,56"), Locale.GERMANY);

        assertThat(bound.get("amount")).isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    void unformattedNumbersKeepIsoParsing() {
        Map<String, Object> bound = InputBinder.bind(
                Map.of("amount", field("number", null)), value("1234.56"), Locale.GERMANY);

        assertThat(bound.get("amount")).isEqualTo(1234.56d);
    }

    @Test
    void rejectionsCarryFieldScopedMessageKeys() {
        assertThatThrownBy(() -> InputBinder.bind(
                Map.of("orderDate", field("date", "yyyy/MM/dd")),
                value("not-a-date"), Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> {
                    Map<String, Object> details = ((TqlException) ex).details();
                    assertThat(details.get("fields")).isInstanceOf(List.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> field = (Map<String, Object>) ((List<?>) details
                            .get("fields")).get(0);
                    assertThat(field).containsEntry("field", "orderDate")
                            .containsEntry("code", "date")
                            .containsEntry("message", "tql.input.date")
                            .containsEntry("format", "yyyy/MM/dd");
                });
    }

    @Test
    void requiredAndRangeViolationsCarryTheirParams() {
        InputField qty = new InputField("integer", true, null, 1, 99, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> InputBinder.bind(Map.of("qty", qty), value(null),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "required")
                        .containsEntry("message", "tql.input.required"));

        assertThatThrownBy(() -> InputBinder.bind(Map.of("qty", qty), value("100"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "max")
                        .containsEntry("max", 99)
                        .containsEntry("message", "tql.input.max"));
    }

    @Test
    void enumViolationsListTheOptions() {
        InputField status = new InputField("string", true, null, null, null, null,
                List.of("open", "closed"), null, null, null, null, null);

        assertThatThrownBy(() -> InputBinder.bind(Map.of("status", status), value("other"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "enum")
                        .containsEntry("options", "open, closed"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstField(TqlException ex) {
        return (Map<String, Object>) ((List<?>) ex.details().get("fields")).get(0);
    }
}
