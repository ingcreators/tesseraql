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
 *   - { name: productName, header: 商品名 }    # localized header label
 *   - { name: qty, column: D }                 # explicit position (letter or 1-based number)
 * </pre>
 *
 * @param name   the SQL parameter / query column name
 * @param header the header label in the file; defaults to {@code name}
 * @param column explicit position as a column letter ({@code D}) or 1-based number
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnSpec(String name, String header, String column) {

    /** The simple string form: {@code columns: [orderNo, qty]}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ColumnSpec of(String name) {
        return new ColumnSpec(name, null, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static ColumnSpec of(@JsonProperty("name") String name,
            @JsonProperty("header") String header, @JsonProperty("column") String column) {
        return new ColumnSpec(name, header, column);
    }

    /** The core mapping, with the column reference resolved to a 0-based index. */
    public io.tesseraql.core.files.ColumnMapping toMapping() {
        return new io.tesseraql.core.files.ColumnMapping(name, header,
                column == null || column.isBlank()
                        ? null : io.tesseraql.core.files.ColumnMapping.parseColumn(column));
    }
}
