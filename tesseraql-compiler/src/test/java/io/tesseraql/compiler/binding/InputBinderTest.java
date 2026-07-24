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
                format, null, null, null, null, null);
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
        InputField qty = new InputField("integer", true, null, new BigDecimal(1),
                new BigDecimal(99), null, null, null, null, null, null, null, null, null, null,
                null);

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
                        .containsEntry("max", new BigDecimal(99))
                        .containsEntry("message", "tql.input.max"));
    }

    @Test
    void enumViolationsListTheOptions() {
        InputField status = new InputField("string", true, null, null, null, null,
                List.of("open", "closed"), null, null, null, null, null, null, null, null,
                null);

        assertThatThrownBy(() -> InputBinder.bind(Map.of("status", status), value("other"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "enum")
                        .containsEntry("options", "open, closed"));
    }

    @Test
    void decimalBoundsAreExactAndFractional() {
        InputField price = new InputField("number", true, null, new BigDecimal("0.5"),
                new BigDecimal("5"), null, null, null, null, null, null, null, null, null, null,
                null);

        // 5.9 violates max: 5 (the old long truncation admitted it), and min: 0.5 is declarable.
        assertThatThrownBy(() -> InputBinder.bind(Map.of("price", price), value("5.9"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "max"));
        assertThatThrownBy(() -> InputBinder.bind(Map.of("price", price), value("0.4"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "min"));
        assertThat(InputBinder.bind(Map.of("price", price), value("0.5"), Locale.ENGLISH))
                .containsEntry("price", 0.5d);

        InputField zeroFloor = new InputField("number", true, null, new BigDecimal(0), null,
                null, null, null, null, null, null, null, null, null, null, null);
        // -0.9 truncated to 0 under the old comparison and slipped past min: 0.
        assertThatThrownBy(() -> InputBinder.bind(Map.of("delta", zeroFloor), value("-0.9"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "min"));
    }

    @Test
    void patternAndMinLengthGateStrings() {
        InputField code = new InputField("string", true, null, null, null, null, null, null,
                null, null, null, null, "[A-Z]{2}-\\d+", 4, null, null);

        assertThat(InputBinder.bind(Map.of("code", code), value("AB-12"), Locale.ENGLISH))
                .containsEntry("code", "AB-12");
        assertThatThrownBy(() -> InputBinder.bind(Map.of("code", code), value("ab-12"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "pattern")
                        .containsEntry("message", "tql.input.pattern"));
        assertThatThrownBy(() -> InputBinder.bind(Map.of("code", code), value("A-1"),
                Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "minLength")
                        .containsEntry("minLength", 4));
    }

    @Test
    void semanticStringFormatsValidate() {
        assertThat(InputBinder.bind(Map.of("mail", field("string", "email")),
                value("dev@example.com"), Locale.ENGLISH))
                .containsEntry("mail", "dev@example.com");
        assertThatThrownBy(() -> InputBinder.bind(Map.of("mail", field("string", "email")),
                value("not-an-email"), Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "email")
                        .containsEntry("message", "tql.input.email"));

        assertThatThrownBy(() -> InputBinder.bind(Map.of("id", field("string", "uuid")),
                value("nope"), Locale.ENGLISH)).isInstanceOf(TqlException.class);
        assertThat(InputBinder.bind(Map.of("id", field("string", "uuid")),
                value("123e4567-e89b-12d3-a456-426614174000"), Locale.ENGLISH))
                .containsKey("id");

        assertThat(InputBinder.bind(Map.of("site", field("string", "url")),
                value("https://example.com/x"), Locale.ENGLISH)).containsKey("site");
        assertThatThrownBy(() -> InputBinder.bind(Map.of("site", field("string", "url")),
                value("ftp://example.com"), Locale.ENGLISH))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(firstField((TqlException) ex))
                        .containsEntry("code", "url"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstField(TqlException ex) {
        return (Map<String, Object>) ((List<?>) ex.details().get("fields")).get(0);
    }
}
