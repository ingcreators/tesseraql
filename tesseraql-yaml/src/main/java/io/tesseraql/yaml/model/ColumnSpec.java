package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One column of a {@code file-import}/{@code file-export} route (design ch. 28). The simple form
 * is just the name; the object form adds the file-side header label and/or an explicit column
 * position, keeping the file-to-SQL correspondence visible in the YAML:
 *
 * <pre>
 * columns: [orderNo, qty]                      # simple: match by header (or order)
 * columns:
 *   - { name: productName, label: 商品名 }     # localized header label
 *   - { name: qty, column: D }                 # explicit position (letter or 1-based number)
 * </pre>
 *
 * <p>{@code type:} ({@code date} / {@code datetime} / {@code number}) parses imported text into
 * typed SQL parameters and produces typed workbook cells on export; {@code format:} is the
 * parse/render pattern (DateTimeFormatter or DecimalFormat, and the Excel cell format). A
 * {@code domain:} says both once (docs/temporal-semantics.md decision 25): the manifest loader
 * merges the domain's {@code type:} and {@code format:} under the column's own, the way it does
 * for an {@code input:} field and a {@code result:} entry — the date a file carries as
 * {@code yyyy/MM/dd} is the business field the request binds and the query reads back. The
 * records stay separate: {@code label} and {@code column} are the file's, and a
 * {@code columns:} list is positional where a field map is keyed.
 *
 * @param name   the SQL parameter / query column name
 * @param label  the human heading in the file — the same word a view column uses; defaults to
 *               {@code name}
 * @param column explicit position as a column letter ({@code D}) or 1-based number
 * @param type   {@code date} / {@code datetime} / {@code number}, or null for plain text
 * @param format the parse/render pattern, e.g. {@code yyyy/MM/dd} or {@code #,##0.00}
 * @param domain an app-level field domain supplying {@code type} and {@code format}
 *               (docs/field-domains.md); its {@code locale:} is not applied — a file has one
 *               locale, the block's — and a route's columns alone are resolved
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnSpec(String name, String label, String column, String type, String format,
        String domain) {

    /** The shape before a column could reference a domain. */
    public ColumnSpec(String name, String label, String column, String type, String format) {
        this(name, label, column, type, format, null);
    }

    /** The five-key factory every caller predating {@code domain:} uses. */
    public static ColumnSpec of(String name, String label, String column, String type,
            String format) {
        return new ColumnSpec(name, label, column, type, format, null);
    }

    /** The simple string form: {@code columns: [orderNo, qty]}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ColumnSpec of(String name) {
        return new ColumnSpec(name, null, null, null, null, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static ColumnSpec of(@JsonProperty("name") String name,
            @JsonProperty("label") String label, @JsonProperty("column") String column,
            @JsonProperty("type") String type, @JsonProperty("format") String format,
            @JsonProperty("domain") String domain) {
        return new ColumnSpec(name, label, column, type, format, domain);
    }

    /**
     * This column with the referenced domain's {@code type} and {@code format} merged
     * underneath its own — the two keys a file column and a field share, and nothing else.
     */
    public ColumnSpec mergedWith(InputField d) {
        return new ColumnSpec(name, label, column,
                type != null ? type : d.type(),
                format != null ? format : d.format(),
                domain);
    }

    /** The core mapping, with the column reference resolved to a 0-based index. */
    public io.tesseraql.core.files.ColumnMapping toMapping() {
        return new io.tesseraql.core.files.ColumnMapping(name, label,
                column == null || column.isBlank()
                        ? null
                        : io.tesseraql.core.files.ColumnMapping.parseColumn(column),
                type, format);
    }
}
