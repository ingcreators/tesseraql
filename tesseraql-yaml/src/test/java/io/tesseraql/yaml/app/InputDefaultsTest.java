package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.InputField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A declared input's {@code default:} is judged by the input's own rules (docs/audit-low-leads.md
 * XD-07b, {@code TQL-YAML-1072}): the literal every omitting request binds used to reach a
 * statement raw — {@code type: number, default: abc} linted clean, booted, answered 200 on
 * omission (400 to a caller sending the same text) and 500 once bound into a numeric compare.
 */
class InputDefaultsTest {

    private static InputField field(String type, Object defaultValue, String format,
            List<String> enumValues, BigDecimal min, BigDecimal max, Integer maxLength,
            String pattern, List<String> columns) {
        return new InputField(type, false, defaultValue, min, max, maxLength, enumValues, null,
                null, null, format, null, pattern, null, null, columns, null, null, null, null,
                null, null, null);
    }

    private static InputField field(String type, Object defaultValue) {
        return field(type, defaultValue, null, null, null, null, null, null, null);
    }

    private static List<Violation> refusals(Map<String, InputField> inputs) {
        return InputDefaults.violations("t", "route 'items.list'", inputs);
    }

    @Test
    void aDefaultTheTypeCannotParseIsRefusedNamingTheInput() {
        assertThat(refusals(Map.of("n", field("number", "abc")))).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1072");
                    assertThat(violation.kind()).isEqualTo(ExportDeclarations.Kind.INVALID);
                    assertThat(violation.key()).isEqualTo("input.n.default");
                    assertThat(violation.message()).contains("app 't'", "route 'items.list'",
                            "input.n.default", "'abc'", "is not a value this input accepts",
                            "not a number");
                });
        assertThat(refusals(Map.of("n", field("integer", "1.5")))).singleElement()
                .extracting(Violation::message).asString().contains("not an integer");
        assertThat(refusals(Map.of("d", field("date", "2026-13-01")))).singleElement()
                .extracting(Violation::message).asString().contains("not a valid date");
        assertThat(refusals(Map.of("d", field("date", "31/12/2026", "yyyy/MM/dd", null, null,
                null, null, null, null)))).singleElement()
                .extracting(Violation::message).asString().contains("not a valid date");
    }

    @Test
    void aDefaultOutsideTheConstraintsIsRefused() {
        assertThat(refusals(Map.of("dir", field("string", "up", null, List.of("asc", "desc"),
                null, null, null, null, null)))).singleElement()
                .extracting(Violation::message).asString()
                .contains("input.dir.default", "'up'", "not one of [asc, desc]");
        assertThat(refusals(Map.of("n", field("integer", 0, null, null, BigDecimal.ONE, null,
                null, null, null)))).singleElement()
                .extracting(Violation::message).asString().contains("below minimum 1");
        assertThat(refusals(Map.of("n", field("number", 10.5, null, null, null,
                BigDecimal.TEN, null, null, null)))).singleElement()
                .extracting(Violation::message).asString().contains("above maximum 10");
        assertThat(refusals(Map.of("code", field("string", "toolong", null, null, null, null, 3,
                null, null)))).singleElement()
                .extracting(Violation::message).asString().contains("exceeds maxLength 3");
        assertThat(refusals(Map.of("sku", field("string", "ab", null, null, null, null, null,
                "[A-Z]{2}", null)))).singleElement()
                .extracting(Violation::message).asString().contains("does not match");
        assertThat(refusals(Map.of("sort", field("sort", "-created", null, null, null, null,
                null, null, List.of("name"))))).singleElement()
                .extracting(Violation::message).asString()
                .contains("outside its declared sort set: created");
    }

    @Test
    void aDefaultOnAnArrayInputIsRefusedBecauseNothingBindsIt() {
        assertThat(refusals(Map.of("ids", field("array", List.of(1, 2))))).singleElement()
                .extracting(Violation::message).asString()
                .contains("input.ids.default", "an array input binds no default:");
    }

    @Test
    void anElementFieldsDefaultIsJudgedUnderItsPath() {
        InputField element = field("integer", "x");
        InputField lines = new InputField("array", false, null, null, null, null, null, null,
                null, null, null, new InputField.InputItems(null, null, Map.of("qty", element)),
                null, null, null, null, null, null, null, null, null, null, null);
        assertThat(refusals(Map.of("lines", lines))).singleElement().satisfies(violation -> {
            assertThat(violation.key()).isEqualTo("input.lines.items.fields.qty.default");
            assertThat(violation.message()).contains("'x'", "not an integer");
        });
    }

    @Test
    void anAcceptableDefaultIsBoundTyped() {
        assertThat(refusals(Map.of(
                "n", field("integer", "5"),
                "d", field("date", "2026-01-15"),
                "f", field("number", "1,234.50", "#,##0.00", null, null, null, null, null, null),
                "dir", field("string", "asc", null, List.of("asc", "desc"), null, null, null,
                        null, null),
                "sort", field("sort", "-name", null, null, null, null, null, null,
                        List.of("name")),
                "flag", field("boolean", true),
                "q", field("string", null)))).isEmpty();
        // The binder binds what the predicate parsed, in the declared type — the raw literal
        // used to travel: a quoted "5" reached the statement as text.
        assertThat(InputDefaults.typed("n", field("integer", "5"))).isEqualTo(5L);
        assertThat(InputDefaults.typed("d", field("date", "2026-01-15")))
                .isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat((BigDecimal) InputDefaults.typed("f", field("number", "1,234.50", "#,##0.00",
                null, null, null, null, null, null))).isEqualByComparingTo("1234.50");
        assertThat(InputDefaults.typed("flag", field("boolean", true))).isEqualTo(true);
        assertThat(InputDefaults.typed("q", field("string", null))).isNull();
        assertThatThrownBy(() -> InputDefaults.typed("n", field("number", "abc")))
                .isInstanceOf(InputValues.Refusal.class)
                .satisfies(ex -> assertThat(((InputValues.Refusal) ex).code())
                        .isEqualTo("number"));
    }
}
