package io.tesseraql.core.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The decision-table evaluator (docs/decision-tables.md): rows are conjunctions of computable
 * cells, alternatives are separate rows, and a lookup never resolves to silent nulls.
 */
class DecisionTablesTest {

    /** The design doc's approval-route archetype: category set, amount ranges, org default. */
    private static DecisionTables.Table approvalRoute(String hitPolicy) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("category", "in");
        inputs.put("amount", "between");
        return DecisionTables.table("approvalRoute", inputs, List.of("route", "level"),
                hitPolicy, null, List.of(
                        new DecisionTables.RowSpec(
                                Map.of("category", List.of("office-supplies", "books"),
                                        "amount", ">= 10000"),
                                Map.of("route", "manager", "level", 1)),
                        new DecisionTables.RowSpec(Map.of("amount", "> 100000"),
                                Map.of("route", "director", "level", 2)),
                        new DecisionTables.RowSpec(Map.of(),
                                Map.of("route", "auto", "level", 0))));
    }

    @Test
    void aRowIsTheConjunctionOfItsCellsAndOrderResolvesFirstHit() {
        DecisionTables.Table table = approvalRoute("first");

        // Both cells hold: the set contains the category AND the amount is in range.
        assertThat(table.evaluate(Map.of("category", "books", "amount", 25000)))
                .containsEntry("route", "manager");
        // The category cell fails, the second row's single cell holds.
        assertThat(table.evaluate(Map.of("category", "travel", "amount", 200000)))
                .containsEntry("route", "director");
        // No conditional row matches: the trailing when-less row answers.
        assertThat(table.evaluate(Map.of("category", "travel", "amount", 500)))
                .containsEntry("route", "auto");
    }

    @Test
    void numbersCompareNumericallyAcrossRepresentations() {
        DecisionTables.Table table = approvalRoute("first");

        // "10000" as a string input and 10000.00 as a decimal both sit on the range boundary.
        assertThat(table.evaluate(Map.of("category", "books", "amount", "10000")))
                .containsEntry("route", "manager");
        assertThat(table.evaluate(Map.of("category", "books",
                "amount", new java.math.BigDecimal("10000.00"))))
                .containsEntry("route", "manager");
        assertThat(table.evaluate(Map.of("category", "books", "amount", 9999.99)))
                .containsEntry("route", "auto");
    }

    @Test
    void aMissWithoutADefaultRaisesInsteadOfResolvingNull() {
        DecisionTables.Table table = DecisionTables.table("fee",
                java.util.Collections.singletonMap("region", null), List.of("fee"), "first", null,
                List.of(new DecisionTables.RowSpec(Map.of("region", "east"),
                        Map.of("fee", 100))));

        assertThatThrownBy(() -> table.evaluate(Map.of("region", "west")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4721");
    }

    @Test
    void uniqueRejectsOverlappingRowsAtCompileTime() {
        Map<String, String> inputs = Map.of("amount", "between");
        assertThatThrownBy(() -> DecisionTables.table("bonus", inputs, List.of("rate"),
                "unique", null, List.of(
                        new DecisionTables.RowSpec(Map.of("amount", "5..15"), Map.of("rate", 1)),
                        new DecisionTables.RowSpec(Map.of("amount", ">= 10"), Map.of("rate", 2)))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4714");
    }

    @Test
    void uniqueAcceptsDisjointRowsAndTheDefaultStaysOutOfHitCounting() {
        Map<String, String> inputs = Map.of("amount", "between");
        DecisionTables.Table table = DecisionTables.table("bonus", inputs, List.of("rate"),
                "unique", null, List.of(
                        new DecisionTables.RowSpec(Map.of("amount", "<= 9"), Map.of("rate", 1)),
                        new DecisionTables.RowSpec(Map.of("amount", ">= 10"), Map.of("rate", 2)),
                        new DecisionTables.RowSpec(Map.of(), Map.of("rate", 0))));

        // The default row wildcards everything; if it counted as a hit, every lookup would be
        // a 4720 multi-hit.
        assertThat(table.evaluate(Map.of("amount", 12))).containsEntry("rate", 2);
    }

    @Test
    void malformedContractsAndCellsCarryTheirCodes() {
        Map<String, String> inputs = Map.of("amount", "between");
        List<String> outputs = List.of("rate");

        assertThatThrownBy(() -> DecisionTables.table("d", Map.of("x", "fuzzy"), outputs,
                null, null, List.of()))
                .hasMessageContaining("TQL-DECISION-4702");
        assertThatThrownBy(() -> DecisionTables.table("d", inputs, outputs, "first", null,
                List.of(new DecisionTables.RowSpec(Map.of("amount", "10 to 20"),
                        Map.of("rate", 1)))))
                .hasMessageContaining("TQL-DECISION-4703");
        assertThatThrownBy(() -> DecisionTables.table("d", inputs, outputs, "first", null,
                List.of(new DecisionTables.RowSpec(Map.of("amount", "5..1"),
                        Map.of("rate", 1)))))
                .hasMessageContaining("TQL-DECISION-4703");
        assertThatThrownBy(() -> DecisionTables.table("d", inputs, outputs, "first", null,
                List.of(new DecisionTables.RowSpec(Map.of("amount", ">= 1"),
                        Map.of("wrong", 1)))))
                .hasMessageContaining("TQL-DECISION-4703");
        assertThatThrownBy(() -> DecisionTables.table("d", inputs, outputs, "first", "default",
                List.of(new DecisionTables.RowSpec(Map.of("amount", ">= 1"),
                        Map.of("rate", 1)))))
                .hasMessageContaining("TQL-DECISION-4704");
    }

    @Test
    void theWiringResolvesInputsFromTheRequestContext() {
        DecisionTables tables = new DecisionTables(List.of(DecisionTables.use("approvalRoute",
                approvalRoute("first"), Map.of(
                        "category", "params.category",
                        "amount", "params.total"))));

        Map<String, Map<String, Object>> decisions = tables.evaluate(Map.of("params",
                Map.of("category", "books", "total", 25000)), null, 0);

        assertThat(decisions.get("approvalRoute"))
                .containsEntry("route", "manager")
                .containsEntry("level", 1);
    }

    @Test
    void aDerivedBooleanRidesTheWiringNotTheCell() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("isOfficer", "bool");
        DecisionTables.Table table = DecisionTables.table("routeBy", inputs, List.of("route"),
                "first", null, List.of(
                        new DecisionTables.RowSpec(Map.of("isOfficer", true),
                                Map.of("route", "fast")),
                        new DecisionTables.RowSpec(Map.of(), Map.of("route", "standard"))));
        DecisionTables tables = new DecisionTables(List.of(DecisionTables.use("routeBy", table,
                Map.of("isOfficer", "principal.role == 'officer'"))));

        assertThat(tables.evaluate(Map.of("principal", Map.of("role", "officer")), null, 0)
                .get("routeBy")).containsEntry("route", "fast");
        assertThat(tables.evaluate(Map.of("principal", Map.of("role", "clerk")), null, 0)
                .get("routeBy")).containsEntry("route", "standard");
    }
}
