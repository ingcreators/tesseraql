package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnValueException;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one predicate both sides judge a declared {@code type:} by, and the read that applies a
 * {@code result:} entry (docs/temporal-semantics.md T3).
 */
class DeclaredKindsTest {

    // ---- the vocabulary, per surface ---------------------------------------------------------

    @Test
    void jsonIsRefusedOnAnInputAndSaysWhereItBelongs() {
        RouteDefinition route = route(Map.of("payload", field("json", null, null)), Map.of());

        List<DeclaredKinds.Violation> violations = DeclaredKinds.inputViolations(route);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).key()).isEqualTo("input.payload.type");
        assertThat(violations.get(0).message())
                .contains("route 'r' input.payload.type: 'json' is not a type a request binds")
                .contains("a result: declaration parses");
    }

    @Test
    void anUnknownInputTypeIsRefusedInsteadOfBoundAsText() {
        RouteDefinition route = route(Map.of("qty", field("integr", null, null)), Map.of());

        assertThat(DeclaredKinds.inputViolations(route)).singleElement()
                .extracting(DeclaredKinds.Violation::message).asString()
                .contains("'integr' is not a type a request binds")
                .doesNotContain("json is a kind");
    }

    @Test
    void everyInputTypeARequestBindsIsAccepted() {
        for (String type : DeclaredKinds.INPUT_TYPES) {
            assertThat(DeclaredKinds.inputViolations(
                    route(Map.of("f", field(type, null, null)), Map.of())))
                    .as(type).isEmpty();
        }
        assertThat(DeclaredKinds.INPUT_TYPES).doesNotContain("json");
    }

    @Test
    void aResultKindOutsideTheFourIsRefused() {
        RouteDefinition route = route(Map.of(),
                Map.of("main", source(Map.of("flag", field("boolean", null, null)))));

        assertThat(DeclaredKinds.resultViolations(route)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.key()).isEqualTo("sources.main.result.flag.type");
                    assertThat(violation.message())
                            .contains("'boolean' is not a kind a result: declaration parses")
                            .contains("date, datetime, json, number");
                });
    }

    @Test
    void aFormatTheKindsParserRefusesIsRefusedAndJsonReadsNone() {
        RouteDefinition route = route(Map.of(), Map.of("main", source(Map.of(
                "d", field("date", "yyyy-bb-dd", null),
                "n", field("number", "#,##0.00", null),
                "j", field("json", "yyyy", null)))));

        List<DeclaredKinds.Violation> violations = DeclaredKinds.resultViolations(route);

        assertThat(violations).extracting(DeclaredKinds.Violation::key)
                .containsExactlyInAnyOrder("sources.main.result.d.format",
                        "sources.main.result.j.format");
        assertThat(violations).extracting(DeclaredKinds.Violation::message)
                .anySatisfy(message -> assertThat(message)
                        .contains("a json declaration reads no format:"));
    }

    @Test
    void aDeclarationOnABindingThatPublishesNoRowsIsRefused() {
        Binding update = new Binding("write.sql", null, "update", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                Map.of("id", field("number", null, null)));
        RouteDefinition route = route(Map.of(), Map.of("header", update));

        assertThat(DeclaredKinds.resultViolations(route)).singleElement()
                .extracting(DeclaredKinds.Violation::message).asString()
                .contains("route 'r' steps.header.result:")
                .contains("publishes none (mode: update)");
    }

    @Test
    void requireThrowsTheFirstViolationWithItsCode() {
        RouteDefinition route = route(Map.of("payload", field("json", null, null)), Map.of());
        List<String> warned = new java.util.ArrayList<>();

        assertThatThrownBy(() -> DeclaredKinds.require(
                DeclaredKinds.inputViolations(route), warned::add))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("'json' is not a type a request binds")
                .extracting(ex -> ((TqlException) ex).code().toString())
                .isEqualTo("TQL-YAML-1064");
        assertThat(warned).hasSize(1);
    }

    // ---- the read ----------------------------------------------------------------------------

    @Test
    void jsonTextBecomesANavigableValueWhoseTextIsCompactJson() {
        Object value = DeclaredKinds.read("payload", field("json", null, null),
                "{\"sku\": \"A-1\", \"qty\": 2, \"tags\": [\"x\", 1.10], \"price\": 1.10}");

        assertThat(value).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) value;
        assertThat(payload.get("sku")).isEqualTo("A-1");
        assertThat(payload.get("qty")).isEqualTo(2);
        assertThat(payload.get("price")).isEqualTo(new BigDecimal("1.10"));
        assertThat(payload.get("tags")).isInstanceOf(List.class);
        // The five String.valueOf surfaces print JSON, never {sku=A-1}.
        assertThat(String.valueOf(value)).isEqualTo(
                "{\"sku\":\"A-1\",\"qty\":2,\"tags\":[\"x\",1.10],\"price\":1.10}");
        assertThat(String.valueOf(payload.get("tags"))).isEqualTo("[\"x\",1.10]");
    }

    @Test
    void aJsonScalarDocumentAndTheNullLiteralAreThemselves() {
        InputField json = field("json", null, null);

        assertThat(DeclaredKinds.read("v", json, "\"text\"")).isEqualTo("text");
        assertThat(DeclaredKinds.read("v", json, "42")).isEqualTo(42);
        assertThat(DeclaredKinds.read("v", json, "true")).isEqualTo(true);
        assertThat(DeclaredKinds.read("v", json, "null")).isNull();
        assertThat(DeclaredKinds.read("v", json, null)).isNull();
    }

    @Test
    void aContainerAnOutboundCallProducedIsRewrappedSoItsTextIsJson() {
        Object value = DeclaredKinds.read("payload", field("json", null, null),
                Map.of("sku", "A-1"));

        assertThat(value).isInstanceOf(JsonValues.JsonObject.class);
        assertThat(String.valueOf(value)).isEqualTo("{\"sku\":\"A-1\"}");
    }

    @Test
    void textThatIsNotJsonIsRefusedNamingTheColumn() {
        assertThatThrownBy(() -> DeclaredKinds.read("payload", field("json", null, null),
                "{sku: A-1"))
                .isInstanceOf(ColumnValueException.class)
                .satisfies(ex -> {
                    ColumnValueException refused = (ColumnValueException) ex;
                    assertThat(refused.column()).isEqualTo("payload");
                    assertThat(refused.value()).isEqualTo("{sku: A-1");
                    assertThat(refused.complaint()).startsWith("is not JSON: ");
                });
    }

    @Test
    void aTextDateInTheDeclaredFormatBecomesTheCanonicalWireText() {
        assertThat(DeclaredKinds.read("d", field("date", "yyyy/MM/dd", null), "2026/01/15"))
                .isEqualTo("2026-01-15");
        assertThat(DeclaredKinds.read("d", field("date", "yyyyMMdd", null), "20240103"))
                .isEqualTo("2024-01-03");
        assertThat(DeclaredKinds.read("t", field("datetime", "yyyy/MM/dd HH:mm", null),
                "2026/01/15 22:30")).isEqualTo("2026-01-15T22:30:00");
        // The kind's default pattern when none is declared.
        assertThat(DeclaredKinds.read("d", field("date", null, null), "2026-01-15"))
                .isEqualTo("2026-01-15");
    }

    @Test
    void aValueAlreadyInTheCanonicalFormReadsUnchangedUnderAnotherFormat() {
        // A native date column declared for its domain's sake arrives canonical already.
        assertThat(DeclaredKinds.read("d", field("date", "yyyy/MM/dd", null), "2026-01-15"))
                .isEqualTo("2026-01-15");
        assertThat(DeclaredKinds.read("t", field("datetime", "yyyy/MM/dd HH:mm", null),
                "2026-01-15T22:30:00.123456")).isEqualTo("2026-01-15T22:30:00.123456");
    }

    @Test
    void aTextDateInNeitherFormIsRefused() {
        assertThatThrownBy(() -> DeclaredKinds.read("d", field("date", "yyyy/MM/dd", null),
                "15.01.2026"))
                .isInstanceOf(ColumnValueException.class)
                .satisfies(ex -> assertThat(((ColumnValueException) ex).complaint())
                        .isEqualTo("is not a valid date (yyyy/MM/dd)"));
    }

    @Test
    void aTextNumberInTheDeclaredPatternBecomesADecimalAndANumberStaysOne() {
        assertThat(DeclaredKinds.read("n", field("number", "#,##0.00", null), "1,234.50"))
                .isEqualTo(new BigDecimal("1234.50"));
        assertThat(DeclaredKinds.read("n", field("number", null, null), "12.5"))
                .isEqualTo(new BigDecimal("12.5"));
        assertThat(DeclaredKinds.read("n", field("number", "#,##0.00", null), 7L))
                .isEqualTo(7L);
        assertThatThrownBy(() -> DeclaredKinds.read("n", field("number", "#,##0.00", null),
                "1.234,50"))
                .isInstanceOf(ColumnValueException.class);
    }

    @Test
    void theReadFailureNamesSourceColumnRowAndKind() {
        ColumnValueException cause = new ColumnValueException("payload", "{oops",
                "is not JSON: Unexpected character");

        TqlException failure = DeclaredKinds.unparseable("main", "payload", 3, "json", cause);

        assertThat(failure.code().toString()).isEqualTo("TQL-SQL-2503");
        assertThat(failure.getMessage()).isEqualTo("TQL-SQL-2503: Source 'main' column"
                + " 'payload' row index 3: the value '{oops' is not JSON: Unexpected character"
                + " - declared json");
        assertThat(failure.details()).containsEntry("column", "payload")
                .containsEntry("row", 3).containsEntry("kind", "json");
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static InputField field(String type, String format, String domain) {
        return new InputField(type, false, null, null, null, null, null, null, null, null,
                format, null, null, null, null, null, domain, null, null, null, null);
    }

    private static Binding source(Map<String, InputField> result) {
        return new Binding("q.sql", null, "query", null, null, null, null, null, null, null,
                null, null, null, null, null, null, result);
    }

    private static RouteDefinition route(Map<String, InputField> input,
            Map<String, Binding> bindings) {
        Map<String, Binding> sources = new java.util.LinkedHashMap<>();
        Map<String, Binding> steps = new java.util.LinkedHashMap<>();
        bindings.forEach((name, binding) -> ("update".equals(binding.mode()) ? steps : sources)
                .put(name, binding));
        return new RouteDefinition("tesseraql/v1", "r", "route", "query-json", input, null,
                null, null, null, null, steps, sources, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
