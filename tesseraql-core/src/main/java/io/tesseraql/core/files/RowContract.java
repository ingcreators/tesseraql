package io.tesseraql.core.files;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What each row of an imported file must satisfy, as plain frozen data
 * (docs/csv-import.md decision 3).
 *
 * <p>It is data rather than a built validator, and that is the whole point. A reviewed import
 * parses twice — once to report, once to commit — and the commit must refuse exactly the rows
 * the review refused, or the agreement check fires and blames the file for having changed. A
 * validator constructed per request cannot promise that: it would re-read whatever the
 * declaration and the code catalogs happen to say at commit time. A record parked beside the
 * read spec can, because it is the same bytes both times.
 *
 * <p>That is also why {@link Column#codes} holds the resolved <em>set of codes</em> and not the
 * catalog's name. The catalog is read once, before the row loop — reading it per value would
 * reload it from its source table per rejection, which is fine for one form submit and
 * catastrophic for a file — and freezing the answer is what makes the report honest about what
 * it is: a snapshot check, taken at upload.
 *
 * @param columns the contract per bind name; a column with no entry is unconstrained
 */
public record RowContract(Map<String, Column> columns) {

    public RowContract {
        // Insertion order, kept: Map.copyOf hands back a salted immutable map, so which of a
        // row's broken constraints is reported first would vary between two runs of the same
        // contract — and a reviewed import runs it twice, once to report and once to commit.
        columns = columns == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** Nothing declared: every row passes, and the pass costs one empty-map iteration. */
    public static RowContract none() {
        return new RowContract(Map.of());
    }

    // Deliberately no isEmpty(): this record is parked as JSON beside the read spec, and core
    // declares no Jackson dependency to annotate one away with. A bare getter would be written
    // out as a property the canonical constructor has no room for, and the commit leg would
    // fail to read back the contract it must be held to. Callers ask columns().isEmpty().

    /**
     * One column's constraints — the constraint half of what an input field declares, with the
     * two keys that mean something else on a file column ({@code type:} and {@code format:},
     * which are the parse pattern there) deliberately absent.
     *
     * @param codes the active codes this column's values must be one of, already resolved
     */
    public record Column(boolean required, BigDecimal min, BigDecimal max, Integer minLength,
            Integer maxLength, String pattern, String format, List<String> enumValues,
            Set<String> codes) {

        public Column {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
            codes = codes == null ? null : Set.copyOf(codes);
        }
    }

    /**
     * The first constraint this row breaks, or null when it satisfies all of them.
     *
     * <p>First rather than every, because a row is reported as one entry with one reason — the
     * same shape the type pass already produces, and the same shape the report's
     * Row / Field / Message table renders. Collecting every violation would give the surface
     * more than it can show and the author more than they can act on in one pass.
     */
    public ColumnValueException firstViolation(Map<String, Object> row) {
        for (Map.Entry<String, Column> declared : columns.entrySet()) {
            ColumnValueException violation = check(declared.getKey(), declared.getValue(),
                    row.get(declared.getKey()));
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    private static ColumnValueException check(String name, Column column, Object value) {
        // A blank cell has already been normalized to null by the type pass, so an absent column
        // and an empty one are the same thing here — which is what `required` on a file means.
        if (value == null || (value instanceof String text && text.isBlank())) {
            return column.required()
                    ? reject(name, null, "is required")
                    : null;
        }
        // Deliberately NOT dispatching on the value's runtime class. A cell arrives typed only
        // when the mapped column declares `type:`, so testing `value instanceof Number` before
        // applying min:/max: made those bounds silently inert on the ordinary
        // `columns: [sku, qty]` form — a declared constraint that lints clean and never runs,
        // which is the defect class this contract exists to retire. Every constraint is applied
        // to the value it is about, coercing for the comparison rather than for the type.
        if (column.min() != null || column.max() != null) {
            BigDecimal decimal = decimalOf(value);
            if (decimal == null) {
                return reject(name, value, "is not a number");
            }
            if (column.min() != null && decimal.compareTo(column.min()) < 0) {
                return reject(name, value, "is below the minimum " + column.min());
            }
            if (column.max() != null && decimal.compareTo(column.max()) > 0) {
                return reject(name, value, "is above the maximum " + column.max());
            }
        }
        // Likewise the text constraints: a `codes:` column whose catalog is numerically keyed
        // holds its codes as text (the catalog's own keys are stringified when the set is
        // frozen), so comparing the cell's text is what makes the two agree.
        String text = String.valueOf(value);
        if (column.minLength() != null && text.length() < column.minLength()) {
            return reject(name, value, "is shorter than " + column.minLength() + " characters");
        }
        if (column.maxLength() != null && text.length() > column.maxLength()) {
            return reject(name, value, "is longer than " + column.maxLength() + " characters");
        }
        if (column.pattern() != null
                && !FieldPatterns.compiled(column.pattern()).matcher(text).matches()) {
            return reject(name, value, "does not match the declared pattern");
        }
        if (column.format() != null && !FieldFormats.matches(column.format(), text)) {
            return reject(name, value, "is not a valid " + column.format());
        }
        if (!column.enumValues().isEmpty() && !column.enumValues().contains(text)) {
            return reject(name, value, "is not one of " + String.join(", ", column.enumValues()));
        }
        if (column.codes() != null && !column.codes().contains(text)) {
            return reject(name, value, "is not an active code");
        }
        return null;
    }

    /** The value as a decimal, however it arrived; null when it is not a number at all. */
    private static BigDecimal decimalOf(Object value) {
        try {
            return value instanceof Number number
                    ? new BigDecimal(number.toString())
                    : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * A violation in the shape the row loop already records, so a constraint failure reaches the
     * report's Field and Value columns the way a bad date does. Anything that lands as a bare
     * message loses exactly the two things the table exists to show.
     */
    private static ColumnValueException reject(String name, Object value, String complaint) {
        String text = value == null ? null : String.valueOf(value);
        return new ColumnValueException(name, text,
                "Column '" + name + "': " + (text == null ? "" : "'" + text + "' ") + complaint);
    }

    /** Builds the frozen contract; columns with nothing declared are left out. */
    public static final class Builder {

        private final Map<String, Column> columns = new LinkedHashMap<>();

        public Builder column(String name, boolean required, BigDecimal min, BigDecimal max,
                Integer minLength, Integer maxLength, String pattern, String format,
                List<String> enumValues, Set<String> codes) {
            boolean declares = required || min != null || max != null || minLength != null
                    || maxLength != null || pattern != null || format != null
                    || (enumValues != null && !enumValues.isEmpty()) || codes != null;
            if (declares) {
                columns.put(name, new Column(required, min, max, minLength, maxLength, pattern,
                        format, enumValues, codes));
            }
            return this;
        }

        public RowContract build() {
            return new RowContract(columns);
        }
    }
}
