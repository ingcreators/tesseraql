package io.tesseraql.yaml.app;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ColumnValues;
import io.tesseraql.core.files.FieldFormats;
import io.tesseraql.core.files.FieldPatterns;
import io.tesseraql.yaml.model.InputField;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules a declared input holds a value to (design ch. 6.3): the type its text parses into
 * and the constraints the parsed value must satisfy — {@code min}/{@code max} on a number,
 * {@code maxLength}/{@code minLength}/{@code pattern}/{@code format}/{@code enum} on text, the
 * column allow-list of a {@code sort}.
 *
 * <p>One judge for every value an input takes: the request binder holds a caller's text to it
 * and answers the field-error envelope, and {@link InputDefaults} holds the author's
 * {@code default:} literal to the same rules at lint and at boot (docs/audit-low-leads.md
 * XD-07b). A {@code codes:} catalog is the binder's alone — it needs the store.
 *
 * <p>A refusal is a {@link Refusal} carrying the field-error {@code code} ({@code integer},
 * {@code enum}, {@code min}, …), the constraint parameters the envelope publishes, and the
 * sentence the log carries; the caller decides what altitude that becomes.
 */
public final class InputValues {

    /**
     * What a formatless {@code type: datetime} field accepts, beside the column default.
     *
     * <p>{@code ViewFields} renders a datetime field as {@code <input type="datetime-local">},
     * whose browsers submit {@code 2026-01-01T09:30} — while {@code ColumnValues}' column default
     * is {@code yyyy-MM-dd HH:mm:ss}, the shape a CSV carries. A field with no {@code format:}
     * therefore could not round-trip through the widget the framework picks for it, and the
     * scaffolder only avoided it by always emitting an explicit {@code format:} of its own.
     *
     * <p>Tried after the column default rather than instead of it: a caller already posting the
     * space-separated form keeps working, and this is purely an addition.
     */
    private static final String WIDGET_DATETIME = "yyyy-MM-dd'T'HH:mm[:ss]";

    private InputValues() {
    }

    /**
     * A value the declaration refuses: {@code code} is the field-error code, {@code params} the
     * constraint the envelope publishes ({@code min}, {@code options}, …), {@code message} the
     * sentence naming the input.
     */
    public static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        private final transient Map<String, ?> params;

        Refusal(String code, Map<String, ?> params, String message) {
            super(message, null, false, false);
            this.code = code;
            this.params = params;
        }

        public String code() {
            return code;
        }

        public Map<String, ?> params() {
            return params;
        }
    }

    /**
     * Parses {@code raw} into the field's type: {@code sort} against its columns,
     * {@code integer}/{@code number}/{@code boolean} as the JDK reads them, a formatted
     * {@code number}/{@code date}/{@code datetime} through the file-transfer column machinery in
     * {@code locale}; anything else stays text.
     */
    public static Object coerce(String name, InputField field, String raw, Locale locale) {
        String type = field.type() == null ? "string" : field.type();
        return switch (type) {
            case "sort" -> coerceSort(name, field, raw);
            case "integer" -> parseLong(name, raw);
            case "number" -> field.format() == null
                    ? parseDouble(name, raw)
                    : parseFormatted(name, field, "number", raw, locale);
            case "boolean" -> Boolean.parseBoolean(raw);
            case "date", "datetime" -> parseFormatted(name, field, type, raw, locale);
            default -> raw;
        };
    }

    /** An array element, coerced by the {@code items.type:}; an unparseable one names its index. */
    public static Object coerceElement(String at, String type, Object element) {
        String text = String.valueOf(element);
        return switch (type) {
            case "integer" -> parseLong(at, text);
            case "number" -> parseDouble(at, text);
            case "boolean" -> Boolean.parseBoolean(text);
            default -> element;
        };
    }

    /**
     * Holds a coerced value to the field's constraints and returns it: decimal-exact
     * {@code min}/{@code max} on a number (5.9 violates {@code max: 5}; a fractional bound is
     * declarable), the length, pattern, semantic {@code format:} and {@code enum} rules on text.
     */
    public static Object constrain(String name, InputField field, Object value) {
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            if (field.min() != null && decimal.compareTo(field.min()) < 0) {
                throw new Refusal("min", Map.of("min", field.min()),
                        "Input '" + name + "' below minimum " + field.min());
            }
            if (field.max() != null && decimal.compareTo(field.max()) > 0) {
                throw new Refusal("max", Map.of("max", field.max()),
                        "Input '" + name + "' above maximum " + field.max());
            }
        }
        if (value instanceof String string) {
            if (field.maxLength() != null && string.length() > field.maxLength()) {
                throw new Refusal("maxLength", Map.of("maxLength", field.maxLength()),
                        "Input '" + name + "' exceeds maxLength " + field.maxLength());
            }
            if (field.minLength() != null && string.length() < field.minLength()) {
                throw new Refusal("minLength", Map.of("minLength", field.minLength()),
                        "Input '" + name + "' is shorter than minLength " + field.minLength());
            }
            if (field.pattern() != null
                    && !FieldPatterns.compiled(field.pattern()).matcher(string).matches()) {
                throw new Refusal("pattern", Map.of("pattern", field.pattern()),
                        "Input '" + name + "' does not match the declared pattern");
            }
            if (field.hasStringFormat() && !FieldFormats.matches(field.format(), string)) {
                throw new Refusal(field.format(), Map.of(),
                        "Input '" + name + "' is not a valid " + field.format());
            }
            if (field.enumValues() != null && !field.enumValues().isEmpty()
                    && !field.enumValues().contains(string)) {
                throw new Refusal("enum",
                        Map.of("options", String.join(", ", field.enumValues())),
                        "Input '" + name + "' is not one of " + field.enumValues());
            }
        }
        return value;
    }

    /** Parses and validates a sort wire value; returns the canonical form unchanged. */
    private static String coerceSort(String name, InputField field, String raw) {
        List<String> allowed = field.columns() == null ? List.of() : field.columns();
        StringBuilder canonical = new StringBuilder();
        for (String token : raw.split(",")) {
            String key = token.strip();
            if (key.isEmpty()) {
                continue;
            }
            String column = key.startsWith("-") ? key.substring(1) : key;
            if (!allowed.contains(column)) {
                throw new Refusal("sort", Map.of("column", column),
                        "Input '" + name + "' names a column outside its declared sort set: "
                                + column);
            }
            if (!canonical.isEmpty()) {
                canonical.append(',');
            }
            canonical.append(key);
        }
        if (canonical.isEmpty()) {
            throw new Refusal("sort", Map.of(), "Input '" + name + "' carries no sort key");
        }
        return canonical.toString();
    }

    private static long parseLong(String name, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new Refusal("integer", Map.of(),
                    "Input '" + name + "' is not an integer: " + raw);
        }
    }

    private static double parseDouble(String name, String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new Refusal("number", Map.of(),
                    "Input '" + name + "' is not a number: " + raw);
        }
    }

    /** Locale-aware parsing through the file-transfer column machinery (mirrors import-side). */
    private static Object parseFormatted(String name, InputField field, String type, String raw,
            Locale locale) {
        try {
            return ColumnValues.parse(
                    new ColumnMapping(name, null, null, type, field.format()), raw, locale);
        } catch (IllegalArgumentException notTheColumnDefault) {
            if (field.format() == null && "datetime".equals(type)) {
                try {
                    return ColumnValues.parse(
                            new ColumnMapping(name, null, null, type, WIDGET_DATETIME), raw,
                            locale);
                } catch (IllegalArgumentException notTheWidgetFormEither) {
                    throw invalidValue(name, field, type, raw);
                }
            }
            throw invalidValue(name, field, type, raw);
        }
    }

    /** The refusal a badly-formatted value earns, naming the declared format when there is one. */
    private static Refusal invalidValue(String name, InputField field, String type, String raw) {
        Map<String, Object> params = field.format() == null
                ? Map.of()
                : Map.of("format", field.format());
        return new Refusal(type, params,
                "Input '" + name + "' is not a valid " + type + ": " + raw);
    }
}
