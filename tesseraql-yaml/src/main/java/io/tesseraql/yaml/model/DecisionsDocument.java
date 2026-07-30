package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One parsed {@code decisions/*.yml} document (docs/decision-tables.md): named decision tables
 * — typed input conditions turned into declared output values — declared once for routes to
 * reference from {@code decide:} via {@code use:}. Aggregation into the app-wide namespace
 * lives in {@link io.tesseraql.yaml.decision.DecisionSets}.
 *
 * @param version   the DSL version, {@code tesseraql/v1}
 * @param decisions declared decisions by name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionsDocument(String version, Map<String, Decision> decisions) {

    public DecisionsDocument {
        decisions = decisions == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(decisions));
    }

    /**
     * One shared decision: what the decision <em>is</em> — its contract (typed inputs and
     * outputs, hit and miss policies) and its rows. What stays local at every reference — the
     * {@code params:} wiring resolving each input from the request — is the operation's use of
     * it, exactly the validation-rule-sets line.
     *
     * @param inputs    typed inputs by name; each row cell constrains one of these
     * @param outputs   typed outputs by name; every row sets all of them
     * @param hitPolicy {@code first} (authored order resolves) or {@code unique} (more than one
     *                  conditional match is an error)
     * @param onMiss    {@code error} (the default: no match raises) or {@code default} (the
     *                  trailing when-less row — or, on a table source, {@code default:} —
     *                  answers)
     * @param rows      the authored rows (YAML source); a trailing row without {@code when:}
     *                  is the default — exactly one of {@code rows}/{@code source}
     * @param source    the app-owned table carrying the rows (docs/decision-tables.md "Two
     *                  sources, one contract"): business users maintain the rows at runtime,
     *                  and the decision evaluates as one generated SELECT in the operation's
     *                  transaction
     * @param defaultOut the outputs answering a miss of a table-backed decision ({@code
     *                  default:}); a YAML-backed decision declares its default as a trailing
     *                  when-less row instead
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(Map<String, Input> inputs, Map<String, Output> outputs,
            String hitPolicy, String onMiss, List<Row> rows, Source source,
            @JsonProperty("default") Map<String, Object> defaultOut) {

        public Decision {
            inputs = inputs == null
                    ? Map.of()
                    : java.util.Collections
                            .unmodifiableMap(new java.util.LinkedHashMap<>(inputs));
            outputs = outputs == null
                    ? Map.of()
                    : java.util.Collections
                            .unmodifiableMap(new java.util.LinkedHashMap<>(outputs));
            rows = rows == null ? List.of() : List.copyOf(rows);
            defaultOut = defaultOut == null
                    ? null
                    : java.util.Collections
                            .unmodifiableMap(new java.util.LinkedHashMap<>(defaultOut));
        }
    }

    /**
     * The table mapping of a table-backed decision: which columns realize each input's match
     * kind, the resolution order, and the columns carrying the outputs. A NULL cell in any
     * mapped column is the wildcard, exactly as an absent YAML cell is.
     *
     * @param table     the app-owned rule table
     * @param id        the rule table's key column joining the {@code set:} child tables,
     *                  defaulting to {@code id}
     * @param match     column realization per input: {@code eq}/{@code bool} → one nullable
     *                  column, {@code between} → a nullable min/max pair, {@code orgSubtree} →
     *                  a nullable unit-id column matched through the managed org closure
     * @param set       child-table realization of each {@code in} input: no child rows =
     *                  wildcard, membership otherwise
     * @param priority  the resolution-order column, required for {@code hitPolicy: first}
     * @param effective optional dated-row window: the {@code [from, to]} column pair matched
     *                  against the reference's {@code effectiveAt:} (default {@code audit.now})
     * @param outputs   output name → column
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String table, String id, Map<String, ColumnMatch> match,
            Map<String, SetMatch> set, String priority, List<String> effective,
            Map<String, String> outputs) {

        public Source {
            match = match == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(match));
            set = set == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(set));
            effective = effective == null ? List.of() : List.copyOf(effective);
            outputs = outputs == null
                    ? Map.of()
                    : java.util.Collections
                            .unmodifiableMap(new java.util.LinkedHashMap<>(outputs));
        }

        /** The rule table's key column, defaulting to {@code id}. */
        public String effectiveId() {
            return id == null || id.isBlank() ? "id" : id;
        }
    }

    /** One input's column realization: exactly one of the three shapes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnMatch(String eq, List<String> between, String subtree) {

        public ColumnMatch {
            between = between == null ? List.of() : List.copyOf(between);
        }
    }

    /**
     * One {@code in} input's normalized child table: {@code key} references the rule row's
     * {@link Source#effectiveId() id}, {@code value} carries one member per child row.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SetMatch(String table, String key, String value) {
    }

    /**
     * One declared input: its type — inline or by {@code domain:} reference — and how row
     * cells compare against it ({@code match:}, defaulting to {@code eq}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(String type, String domain, String match) {
    }

    /**
     * One declared output: its type, inline or by {@code domain:} reference; an {@code enum}
     * gives the compiler the full value space for consumption-side exhaustiveness lints.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(String type, String domain,
            @JsonProperty("enum") List<Object> allowed) {

        public Output {
            allowed = allowed == null ? List.of() : List.copyOf(allowed);
        }
    }

    /**
     * One authored row: the conjunction of its {@code when:} cells (absent cell = wildcard)
     * and the outputs it sets. A row without {@code when:} is the default row and must be
     * last.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(Map<String, Object> when, Map<String, Object> out) {

        public Row {
            when = when == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(when));
            out = out == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(out));
        }
    }
}
