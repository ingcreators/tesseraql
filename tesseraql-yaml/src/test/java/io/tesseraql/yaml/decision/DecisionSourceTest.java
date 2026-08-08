package io.tesseraql.yaml.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.decision.DecisionTables;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.DecisionsDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The generated lookup of a table-backed decision (docs/decision-tables.md "Evaluation"):
 * ordinary SQL — one {@code (col IS NULL OR col ⟨op⟩ ?)} arm per mapped column, an EXISTS per
 * {@code in} child table and per {@code subtree} closure test, the effective window, and a
 * portable single-row fetch for {@code hitPolicy: first}.
 */
class DecisionSourceTest {

    /** The design doc's shippingFee archetype, plus a subtree input. */
    private static DecisionsDocument.Decision shippingFee(String hitPolicy, String priority) {
        Map<String, DecisionsDocument.Input> inputs = new LinkedHashMap<>();
        inputs.put("weight", new DecisionsDocument.Input("number", null, "between"));
        inputs.put("region", new DecisionsDocument.Input("string", null, null));
        inputs.put("category", new DecisionsDocument.Input("string", null, "in"));
        inputs.put("dept", new DecisionsDocument.Input("string", null, "subtree"));
        Map<String, DecisionsDocument.Output> outputs = new LinkedHashMap<>();
        outputs.put("fee", new DecisionsDocument.Output("number", null, null));
        outputs.put("carrier", new DecisionsDocument.Output("string", null, null));
        Map<String, DecisionsDocument.ColumnMatch> match = new LinkedHashMap<>();
        match.put("weight", new DecisionsDocument.ColumnMatch(null,
                List.of("weight_min", "weight_max"), null));
        match.put("region", new DecisionsDocument.ColumnMatch("region", null, null));
        match.put("dept", new DecisionsDocument.ColumnMatch(null, null, "dept_unit"));
        Map<String, DecisionsDocument.SetMatch> set = Map.of("category",
                new DecisionsDocument.SetMatch("fee_rule_categories", "rule_id", "category"));
        Map<String, String> outputColumns = new LinkedHashMap<>();
        outputColumns.put("fee", "fee");
        outputColumns.put("carrier", "carrier");
        DecisionsDocument.Source source = new DecisionsDocument.Source("shipping_fee_rules",
                null, match, set, priority, List.of("valid_from", "valid_to"), outputColumns);
        return new DecisionsDocument.Decision(inputs, outputs, hitPolicy, null, null, source,
                null);
    }

    @Test
    void theGeneratedSelectIsOrdinaryLoggableSql() {
        DecisionTables.TableSource compiled = DecisionSets.compileSource("shippingFee",
                shippingFee("first", "priority"), "postgres");

        assertThat(compiled.sql()).isEqualTo("select r.fee as fee, r.carrier as carrier"
                + " from shipping_fee_rules r where 1 = 1"
                + " and (r.weight_min is null or r.weight_min <= ?)"
                + " and (r.weight_max is null or r.weight_max >= ?)"
                + " and (r.region is null or r.region = ?)"
                + " and (not exists (select 1 from fee_rule_categories s"
                + " where s.rule_id = r.id)"
                + " or exists (select 1 from fee_rule_categories s"
                + " where s.rule_id = r.id and s.category = ?))"
                + " and (r.dept_unit is null or exists (select 1 from tql_org_closure oc"
                + " where oc.ancestor_id = r.dept_unit and oc.descendant_id = ?))"
                + " and (r.valid_from is null or r.valid_from <= ?)"
                + " and (r.valid_to is null or r.valid_to >= ?)"
                + " order by r.priority limit ?");
        assertThat(compiled.binds()).containsExactly("weight", "weight", "region", "category",
                "dept", DecisionTables.TableSource.EFFECTIVE_AT,
                DecisionTables.TableSource.EFFECTIVE_AT, DecisionTables.TableSource.LIMIT);
        assertThat(compiled.outputs()).containsExactly("fee", "carrier");
    }

    @Test
    void oracleAndSqlServerGetTheOffsetFetchForm() {
        DecisionTables.TableSource compiled = DecisionSets.compileSource("shippingFee",
                shippingFee("first", "priority"), "oracle");

        assertThat(compiled.sql()).endsWith("order by r.priority"
                + " offset 0 rows fetch next ? rows only");
    }

    @Test
    void uniqueSkipsTheOrderingAndReadsForAmbiguity() {
        DecisionTables.TableSource compiled = DecisionSets.compileSource("shippingFee",
                shippingFee("unique", null), "postgres");

        assertThat(compiled.sql()).doesNotContain("order by").doesNotContain("limit");
        assertThat(compiled.unique()).isTrue();
    }

    @Test
    void firstWithoutAPriorityColumnFails() {
        assertThatThrownBy(() -> DecisionSets.compileSource("shippingFee",
                shippingFee("first", null), "postgres"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4702")
                .hasMessageContaining("priority");
    }

    @Test
    void anIdentifierThatIsNotAPlainSqlNameFails() {
        DecisionsDocument.Decision decision = shippingFee("first", "priority; drop table x");

        assertThatThrownBy(() -> DecisionSets.compileSource("shippingFee", decision, "postgres"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4702")
                .hasMessageContaining("not a plain SQL identifier");
    }

    @Test
    void theMappingMustCoverTheInputsExactly() {
        DecisionsDocument.Decision decision = shippingFee("first", "priority");
        Map<String, DecisionsDocument.ColumnMatch> match = new LinkedHashMap<>(
                decision.source().match());
        match.remove("region");
        DecisionsDocument.Decision unmapped = new DecisionsDocument.Decision(decision.inputs(),
                decision.outputs(), "first", null, null,
                new DecisionsDocument.Source("shipping_fee_rules", null, match,
                        decision.source().set(), "priority", List.of(),
                        decision.source().outputs()),
                null);

        assertThatThrownBy(() -> DecisionSets.compileSource("shippingFee", unmapped, "postgres"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4702")
                .hasMessageContaining("region");
    }

    @Test
    void aDefaultAnswersOnlyWithExactlyTheOutputsAndLegalValues() {
        DecisionsDocument.Decision decision = shippingFee("first", "priority");
        DecisionsDocument.Decision withDefault = new DecisionsDocument.Decision(
                decision.inputs(), decision.outputs(), "first", null, null, decision.source(),
                Map.of("fee", 0));

        assertThatThrownBy(
                () -> DecisionSets.compileSource("shippingFee", withDefault, "postgres"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4702")
                .hasMessageContaining("carrier");
    }
}
