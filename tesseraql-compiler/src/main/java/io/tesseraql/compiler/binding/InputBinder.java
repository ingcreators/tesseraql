package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ColumnValues;
import io.tesseraql.yaml.model.InputField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Validates and coerces declared route inputs into typed effective values (design ch. 6.3, 33.1).
 *
 * <p>Only declared inputs are produced (input whitelisting). Missing inputs fall back to their
 * declared default; required inputs without a value are rejected. Type coercion and the
 * {@code min}/{@code max}/{@code maxLength}/{@code enum} constraints are applied here.
 *
 * <p>{@code date}/{@code datetime}/{@code number} inputs parse with the negotiated request locale
 * and the field's {@code format} pattern, through the same machinery as file-transfer columns
 * (roadmap Phase 22). Rejections raise {@code TQL-FIELD-2001} with a field-scoped error entry —
 * a stable code and a {@code tql.input.<code>} message key the error renderer localizes — so a
 * bad form value surfaces inline next to its input like a validation violation.
 */
public final class InputBinder {

    private static final TqlErrorCode VALIDATION = new TqlErrorCode(TqlDomain.FIELD, 2001);

    private InputBinder() {
    }

    /** Binds with the English/default locale (locale-less callers and tests). */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup) {
        return bind(inputs, rawLookup, Locale.ENGLISH);
    }

    /**
     * Binds raw string-valued request inputs to typed effective values.
     *
     * @param inputs the declared input fields
     * @param rawLookup resolves a raw request value by input name (e.g. an HTTP query parameter)
     * @param locale the negotiated request locale driving date/number parsing
     */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup, Locale locale) {
        Map<String, Object> effective = new LinkedHashMap<>();
        for (Map.Entry<String, InputField> entry : inputs.entrySet()) {
            String name = entry.getKey();
            InputField field = entry.getValue();
            String raw = rawLookup.apply(name);

            if (raw == null || raw.isEmpty()) {
                if (field.required()) {
                    throw reject(name, "required", Map.of(),
                            "Missing required input '" + name + "'");
                }
                if (field.defaultValue() != null) {
                    effective.put(name, field.defaultValue());
                }
                continue;
            }
            effective.put(name, validate(name, field, coerce(name, field, raw, locale)));
        }
        return effective;
    }

    private static Object coerce(String name, InputField field, String raw, Locale locale) {
        String type = field.type() == null ? "string" : field.type();
        return switch (type) {
            case "integer" -> parseLong(name, raw);
            case "number" -> field.format() == null
                    ? parseDouble(name, raw)
                    : parseFormatted(name, field, "number", raw, locale);
            case "boolean" -> Boolean.parseBoolean(raw);
            case "date", "datetime" -> parseFormatted(name, field, type, raw, locale);
            default -> raw;
        };
    }

    private static Object validate(String name, InputField field, Object value) {
        if (value instanceof Number number) {
            long asLong = number.longValue();
            if (field.min() != null && asLong < field.min()) {
                throw reject(name, "min", Map.of("min", field.min()),
                        "Input '" + name + "' below minimum " + field.min());
            }
            if (field.max() != null && asLong > field.max()) {
                throw reject(name, "max", Map.of("max", field.max()),
                        "Input '" + name + "' above maximum " + field.max());
            }
        }
        if (value instanceof String string) {
            if (field.maxLength() != null && string.length() > field.maxLength()) {
                throw reject(name, "maxLength", Map.of("maxLength", field.maxLength()),
                        "Input '" + name + "' exceeds maxLength " + field.maxLength());
            }
            if (field.enumValues() != null && !field.enumValues().isEmpty()
                    && !field.enumValues().contains(string)) {
                throw reject(name, "enum",
                        Map.of("options", String.join(", ", field.enumValues())),
                        "Input '" + name + "' is not one of " + field.enumValues());
            }
        }
        return value;
    }

    private static long parseLong(String name, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw reject(name, "integer", Map.of(),
                    "Input '" + name + "' is not an integer: " + raw);
        }
    }

    private static double parseDouble(String name, String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw reject(name, "number", Map.of(),
                    "Input '" + name + "' is not a number: " + raw);
        }
    }

    /** Locale-aware parsing through the file-transfer column machinery (mirrors import-side). */
    private static Object parseFormatted(String name, InputField field, String type, String raw,
            Locale locale) {
        try {
            return ColumnValues.parse(
                    new ColumnMapping(name, null, null, type, field.format()), raw, locale);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> params = field.format() == null
                    ? Map.of()
                    : Map.of("format", field.format());
            throw reject(name, type, params,
                    "Input '" + name + "' is not a valid " + type + ": " + raw);
        }
    }

    /** A field-scoped rejection: stable code, localizable message key, constraint params. */
    private static TqlException reject(String name, String code, Map<String, ?> params,
            String logMessage) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("field", name);
        field.put("code", code);
        field.put("message", "tql.input." + code);
        field.putAll(params);
        return TqlException.builder(VALIDATION)
                .message(logMessage)
                .details(Map.of("fields", List.of(field)))
                .build();
    }
}
