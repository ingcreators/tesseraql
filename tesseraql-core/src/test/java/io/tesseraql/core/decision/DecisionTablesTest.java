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

    /**
     * A strict comparator is an open end, whatever the scale of the literal or the input
     * (docs/audit-low-leads.md, G8). {@code > 100000} used to compile to {@code >= 100001} —
     * one unit in the literal's own scale — so every amount strictly between the two, the
     * ordinary case on a money column, matched the row below it.
     */
    @Test
    void aStrictComparatorIsAnOpenEndAtEveryScale() {
        DecisionTables.Table table = approvalRoute("first");

        assertThat(table.evaluate(Map.of("category", "travel", "amount", 100000)))
                .containsEntry("route", "auto");
        assertThat(table.evaluate(Map.of("category", "travel",
                "amount", new java.math.BigDecimal("100000.50"))))
                .containsEntry("route", "director");
        assertThat(table.evaluate(Map.of("category", "travel",
                "amount", new java.math.BigDecimal("100000.01"))))
                .containsEntry("route", "director");
        assertThat(table.evaluate(Map.of("category", "travel", "amount", 100001)))
                .containsEntry("route", "director");

        // The upper end, and a literal with its own decimals: the input's scale is unrelated.
        Map<String, String> inputs = Map.of("deltaPct", "between");
        DecisionTables.Table lane = DecisionTables.table("lane", inputs, List.of("route"),
                "first", null, List.of(
                        new DecisionTables.RowSpec(Map.of("deltaPct", "< 3"),
                                Map.of("route", "auto")),
                        new DecisionTables.RowSpec(Map.of(), Map.of("route", "review"))));
        assertThat(lane.evaluate(Map.of("deltaPct", new java.math.BigDecimal("2.99"))))
                .containsEntry("route", "auto");
        assertThat(lane.evaluate(Map.of("deltaPct", 3))).containsEntry("route", "review");
        DecisionTables.Table scaled = DecisionTables.table("scaled", inputs, List.of("route"),
                "first", null, List.of(
                        new DecisionTables.RowSpec(Map.of("deltaPct", "> 100000.00"),
                                Map.of("route", "director")),
                        new DecisionTables.RowSpec(Map.of(), Map.of("route", "auto"))));
        assertThat(scaled.evaluate(Map.of("deltaPct", new java.math.BigDecimal("100000.001"))))
                .containsEntry("route", "director");
    }

    /**
     * A unique table partitioned at a point by {@code <= n} / {@code > n} is what the overlap
     * check certifies, and it must then answer for every input — the rounding left
     * {@code (n, n+1)} in neither row, a 4721 miss on a table lint had just passed. Two closed
     * ends meeting at a point still overlap, and an open end against a closed one does not.
     */
    @Test
    void aPartitionAtAPointAnswersForEveryInputAndTheOverlapCheckSeesOpenEnds() {
        Map<String, String> inputs = Map.of("amount", "between");
        DecisionTables.Table partition = DecisionTables.table("partition", inputs,
                List.of("lane"), "unique", null, List.of(
                        new DecisionTables.RowSpec(Map.of("amount", "<= 100000"),
                                Map.of("lane", "low")),
                        new DecisionTables.RowSpec(Map.of("amount", "> 100000"),
                                Map.of("lane", "high"))));
        assertThat(partition.evaluate(Map.of("amount", 100000))).containsEntry("lane", "low");
        assertThat(partition.evaluate(Map.of("amount", new java.math.BigDecimal("100000.50"))))
                .containsEntry("lane", "high");

        assertThatThrownBy(() -> DecisionTables.table("touching", inputs, List.of("lane"),
                "unique", null, List.of(
                        new DecisionTables.RowSpec(Map.of("amount", "<= 100000"),
                                Map.of("lane", "low")),
                        new DecisionTables.RowSpec(Map.of("amount", ">= 100000"),
                                Map.of("lane", "high")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4714");
        assertThatThrownBy(() -> DecisionTables.table("open-inside", inputs, List.of("lane"),
                "unique", null, List.of(
                        new DecisionTables.RowSpec(Map.of("amount", "< 100001"),
                                Map.of("lane", "low")),
                        new DecisionTables.RowSpec(Map.of("amount", "> 100000"),
                                Map.of("lane", "high")))))
                .as("(100000, 100001) is in both rows")
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4714");
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
                Map.of("category", "books", "total", 25000)), null, null);

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

        assertThat(tables.evaluate(Map.of("principal", Map.of("role", "officer")), null, null)
                .get("routeBy")).containsEntry("route", "fast");
        assertThat(tables.evaluate(Map.of("principal", Map.of("role", "clerk")), null, null)
                .get("routeBy")).containsEntry("route", "standard");
    }
}
