package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Validates and coerces declared route inputs into typed effective values (design ch. 6.3, 33.1).
 *
 * <p>Only declared inputs are produced (input whitelisting). Missing inputs fall back to their
 * declared default; required inputs without a value are rejected. Type coercion and the
 * {@code min}/{@code max}/{@code maxLength}/{@code enum} constraints are applied here.
 */
public final class InputBinder {

    private static final TqlErrorCode VALIDATION = new TqlErrorCode(TqlDomain.FIELD, 2001);

    private InputBinder() {
    }

    /**
     * Binds raw string-valued request inputs to typed effective values.
     *
     * @param inputs the declared input fields
     * @param rawLookup resolves a raw request value by input name (e.g. an HTTP query parameter)
     */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup) {
        Map<String, Object> effective = new LinkedHashMap<>();
        for (Map.Entry<String, InputField> entry : inputs.entrySet()) {
            String name = entry.getKey();
            InputField field = entry.getValue();
            String raw = rawLookup.apply(name);

            if (raw == null || raw.isEmpty()) {
                if (field.required()) {
                    throw new TqlException(VALIDATION, "Missing required input '" + name + "'");
                }
                if (field.defaultValue() != null) {
                    effective.put(name, field.defaultValue());
                }
                continue;
            }
            effective.put(name, validate(name, field, coerce(name, field, raw)));
        }
        return effective;
    }

    private static Object coerce(String name, InputField field, String raw) {
        String type = field.type() == null ? "string" : field.type();
        return switch (type) {
            case "integer" -> parseLong(name, raw);
            case "number" -> parseDouble(name, raw);
            case "boolean" -> Boolean.parseBoolean(raw);
            default -> raw;
        };
    }

    private static Object validate(String name, InputField field, Object value) {
        if (value instanceof Number number) {
            long asLong = number.longValue();
            if (field.min() != null && asLong < field.min()) {
                throw new TqlException(VALIDATION,
                        "Input '" + name + "' below minimum " + field.min());
            }
            if (field.max() != null && asLong > field.max()) {
                throw new TqlException(VALIDATION,
                        "Input '" + name + "' above maximum " + field.max());
            }
        }
        if (value instanceof String string) {
            if (field.maxLength() != null && string.length() > field.maxLength()) {
                throw new TqlException(VALIDATION,
                        "Input '" + name + "' exceeds maxLength " + field.maxLength());
            }
            if (field.enumValues() != null && !field.enumValues().isEmpty()
                    && !field.enumValues().contains(string)) {
                throw new TqlException(VALIDATION,
                        "Input '" + name + "' is not one of " + field.enumValues());
            }
        }
        return value;
    }

    private static long parseLong(String name, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new TqlException(VALIDATION, "Input '" + name + "' is not an integer: " + raw);
        }
    }

    private static double parseDouble(String name, String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new TqlException(VALIDATION, "Input '" + name + "' is not a number: " + raw);
        }
    }
}
