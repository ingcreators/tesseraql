package io.tesseraql.scim;

import java.util.Map;

/** Coerces a SCIM count-query result row into an integer total (design ch. 10.15). */
final class ScimCount {

    private ScimCount() {
    }

    /**
     * Reads the count from a single-row result: the first column's value as an integer, or
     * {@code fallback} when the row or value is absent or non-numeric.
     */
    static int toInt(Map<String, Object> row, int fallback) {
        if (row == null || row.isEmpty()) {
            return fallback;
        }
        Object value = row.values().iterator().next();
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
