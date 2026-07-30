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
     *                  trailing when-less row answers)
     * @param rows      the authored rows; a trailing row without {@code when:} is the default
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(Map<String, Input> inputs, Map<String, Output> outputs,
            String hitPolicy, String onMiss, List<Row> rows) {

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
        }
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
