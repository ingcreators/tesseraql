package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One declarative validation rule of a command's {@code validate:} block (roadmap Phase 19).
 *
 * <p>A rule is either a cross-field expression in the core expression language ({@code rule:},
 * truthy means valid) or a validation SQL file ({@code file:}, a SELECT whose returned rows are
 * the violations — uniqueness, existence, balance checks). SQL rules execute inside the command's
 * transaction, before any step writes. Every violation is reported against a stable error model:
 * the rule id, a field path, a rule code, and an optional message key (translated in Phase 22).
 *
 * @param when    optional guard expression; a falsy guard skips the rule
 * @param rule    the expression that must hold for the input to be valid
 * @param file    the validation SQL file relative to the owning YAML file's directory; each
 *                returned row is a violation, and its {@code field}/{@code code}/{@code message}
 *                columns override the declared defaults
 * @param params  mapping of bind name to a source expression such as {@code body.email}
 * @param field   the input field path violations are reported against
 * @param code    stable rule code carried in the error payload, defaulting to the rule id
 * @param message optional message key for localized rendering (roadmap Phase 22)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationRule(String when, String rule, String file, Map<String, String> params,
        String field, String code, String message) {

    public ValidationRule {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Whether this is a cross-field expression rule. */
    public boolean isExpression() {
        return rule != null && !rule.isBlank();
    }

    /** Whether this is a validation SQL rule. */
    public boolean isSql() {
        return file != null && !file.isBlank();
    }
}
