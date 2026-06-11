package io.tesseraql.core.files;

/**
 * One column of a file transfer (design ch. 28): the SQL-facing name, optionally the file-facing
 * header label (when the file's headers differ from the parameter names, e.g. localized headers),
 * optionally an explicit 0-based column position, and optionally a value type and format. With no
 * position the column matches by header label (header row present) or by its declared order
 * (positional files).
 *
 * <p>The type ({@code date}, {@code datetime}, {@code number}) parses imported text into typed
 * SQL parameters and drives typed (date/numeric) workbook cells on export; the format is the
 * parse/render pattern - {@link java.time.format.DateTimeFormatter} for temporal columns,
 * {@link java.text.DecimalFormat} for numbers, and the Excel cell format in workbook output.
 *
 * @param name   the SQL parameter / query column name
 * @param header the header label in the file; defaults to {@code name}
 * @param index  explicit 0-based column position, or null to match by header/order
 * @param type   {@code date} / {@code datetime} / {@code number}, or null for plain text
 * @param format the parse/render pattern, or null for the type's default
 */
public record ColumnMapping(String name, String header, Integer index, String type,
        String format) {

    public ColumnMapping(String name, String header, Integer index) {
        this(name, header, index, null, null);
    }

    public static ColumnMapping of(String name) {
        return new ColumnMapping(name, null, null, null, null);
    }

    /** The label expected (import) or written (export) in the header row. */
    public String effectiveHeader() {
        return header == null || header.isBlank() ? name : header;
    }

    /**
     * Parses a column reference: a letter reference ({@code A}, {@code D}, {@code AB}) or a
     * 1-based number, to a 0-based index.
     */
    public static int parseColumn(String reference) {
        String ref = reference.trim().toUpperCase(java.util.Locale.ROOT);
        if (ref.matches("[0-9]+")) {
            int oneBased = Integer.parseInt(ref);
            if (oneBased < 1) {
                throw new IllegalArgumentException("Column numbers are 1-based: " + reference);
            }
            return oneBased - 1;
        }
        if (!ref.matches("[A-Z]+")) {
            throw new IllegalArgumentException("Not a column reference: " + reference);
        }
        int index = 0;
        for (int i = 0; i < ref.length(); i++) {
            index = index * 26 + (ref.charAt(i) - 'A' + 1);
        }
        return index - 1;
    }
}
