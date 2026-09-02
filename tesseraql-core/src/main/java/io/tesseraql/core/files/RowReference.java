package io.tesseraql.core.files;

/**
 * Where one data row sits in the file the author uploaded (docs/csv-import.md decision 8).
 *
 * <p>The parse counts data rows, and that ordinal is the wrong number to show anyone: a header
 * row and any rows skipped above the table shift it, so "row 3" in a report and "row 3" in the
 * author's editor are different rows. It is also the wrong <em>word</em> for a workbook, where a
 * location is a sheet and a row rather than a line.
 *
 * <p>So the format answers instead of the report assuming. The report renders this as a label,
 * the way {@link io.tesseraql.core.files.FileTransferService.RowError} entries carry their link
 * as a value: same slot, one more thing the format fills.
 *
 * @param sheet   the sheet the row is on, or null when the format has none — or has one whose
 *                name the declaration never gave
 * @param number  the row as the author counts it: a file line for a text format, a sheet row
 *                for a workbook
 * @param sheeted whether the number is a sheet row rather than a file line. Separate from
 *                {@code sheet}, because a workbook import that does not declare {@code sheet:}
 *                reads the first sheet and still counts rows — "Line 3" would be the wrong word
 *                for it, and naming a sheet the author never wrote would be worse
 */
public record RowReference(String sheet, long number, boolean sheeted) {

    /** A line of a text file. */
    public static RowReference line(long number) {
        return new RowReference(null, number, false);
    }

    /** A row of a workbook sheet; {@code sheet} may be null when the declaration named none. */
    public static RowReference sheetRow(String sheet, long number) {
        return new RowReference(sheet, number, true);
    }
}
