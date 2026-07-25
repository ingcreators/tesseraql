package io.tesseraql.yaml.model;

import java.util.List;
import java.util.Map;

/**
 * One parsed {@code rules/*.yml} document (docs/validation-rule-sets.md): named validation
 * rules — a cross-field expression or a validation SQL file with a bind contract — declared
 * once for routes to reference from {@code validate:} via {@code use:}. Aggregation into the
 * app-wide namespace lives in {@link io.tesseraql.yaml.rules.ValidationRuleSets}.
 *
 * @param version the DSL version, {@code tesseraql/v1}
 * @param rules declared rules by name
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record RuleSetsDocument(String version, Map<String, RuleSet> rules) {

    public RuleSetsDocument {
        rules = rules == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(rules));
    }

    /**
     * One shared rule: what the rule <em>is</em>. What stays local at every reference —
     * {@code params:} wiring, the {@code field:} reporting target, {@code when:} guards — is
     * the operation's use of it (the field-domains line, applied to rules).
     *
     * @param rule    cross-field expression (exactly one of rule/file)
     * @param file    validation SQL, relative to the rules document
     * @param binds   the bind contract a reference's {@code params:} must satisfy exactly;
     *                ambient binds ({@code principal.*}, {@code audit.*}) never appear here
     * @param code    default stable rule code
     * @param message default message key
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleSet(String rule, String file, List<String> binds, String code,
            String message) {

        public RuleSet {
            binds = binds == null ? List.of() : List.copyOf(binds);
        }
    }
}
