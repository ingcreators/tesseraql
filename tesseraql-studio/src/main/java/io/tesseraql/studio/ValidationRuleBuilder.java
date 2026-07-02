package io.tesseraql.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a route's {@code validate:} YAML block for one rule from a chosen operation and inputs
 * (Studio backlog: the validation rule builder). Expression rules use the core expression language
 * ({@code && || == != < > <= >=}); a SQL rule references a validation SQL file. The output is meant
 * to be copied into a command route's {@code validate:} section.
 */
public final class ValidationRuleBuilder {

    private ValidationRuleBuilder() {
    }

    /**
     * The {@code validate:} snippet for {@code operation}. {@code source} is the input namespace
     * ({@code body}/{@code params}/{@code path}); {@code field} the field the rule checks;
     * {@code value}/{@code value2} the operation's operands (or, for {@code expression}, the raw
     * expression, and for {@code sql}, the validation SQL file). Returns a {@code # ...} comment when
     * a required input is missing.
     */
    public static String generate(String operation, String source, String field, String value,
            String value2, String id, String code, String message, String when) {
        String ruleId = trim(id);
        String fieldName = trim(field);
        String op = operation == null ? "" : operation;
        if (ruleId == null) {
            return "# Enter a rule id.";
        }
        if (fieldName == null) {
            return "# Enter the field the rule checks.";
        }
        String ref = (blank(source) ? "body" : source.trim()) + "." + fieldName;
        String rule = null;
        String file = null;
        switch (op) {
            case "required" -> rule = ref + " != null";
            case "not-empty" -> rule = ref + " != null && " + ref + " != ''";
            case "min" -> rule = require(value, () -> ref + " >= " + value.trim());
            case "max" -> rule = require(value, () -> ref + " <= " + value.trim());
            case "range" -> rule = (blank(value) || blank(value2))
                    ? null
                    : ref + " >= " + value.trim() + " && " + ref + " <= " + value2.trim();
            case "equals" -> rule = require(value, () -> ref + " == " + literal(value));
            case "not-equals" -> rule = require(value, () -> ref + " != " + literal(value));
            case "one-of" -> rule = require(value, () -> oneOf(ref, value));
            case "expression" -> rule = require(value, value::trim);
            case "sql" -> file = trim(value);
            default -> {
                return "# Choose an operation.";
            }
        }
        if ("sql".equals(op) && file == null) {
            return "# Enter the validation SQL file name.";
        }
        if (!"sql".equals(op) && rule == null) {
            return "# Enter the operation's value(s).";
        }
        StringBuilder yaml = new StringBuilder("validate:\n  ").append(ruleId).append(":\n");
        if (!blank(when)) {
            yaml.append("    when: ").append(when.trim()).append('\n');
        }
        if (file != null) {
            yaml.append("    file: ").append(file).append('\n');
        } else {
            yaml.append("    rule: ").append(rule).append('\n');
        }
        yaml.append("    field: ").append(fieldName).append('\n');
        yaml.append("    code: ").append(blank(code) ? ruleId : code.trim()).append('\n');
        if (!blank(message)) {
            yaml.append("    message: ").append(message.trim()).append('\n');
        }
        return yaml.toString();
    }

    /** {@code ref == a || ref == b || …} from a comma-separated value list. */
    private static String oneOf(String ref, String csv) {
        List<String> terms = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                terms.add(ref + " == " + literal(trimmed));
            }
        }
        return terms.isEmpty() ? null : String.join(" || ", terms);
    }

    /** A numeric value verbatim; anything else single-quoted (a string literal). */
    private static String literal(String value) {
        String trimmed = value.trim();
        if (isNumeric(trimmed) || "true".equals(trimmed) || "false".equals(trimmed)
                || "null".equals(trimmed)) {
            return trimmed;
        }
        return "'" + trimmed.replace("'", "\\'") + "'";
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String require(String value, java.util.function.Supplier<String> build) {
        return blank(value) ? null : build.get();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return blank(value) ? null : value.trim();
    }
}
