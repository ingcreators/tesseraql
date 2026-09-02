package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.files.RowReference;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The reviewed import's feeder of the shared report (docs/csv-import.md decision 4,
 * docs/bulk-report.md decision 1): a parse pass's rejections become {@link ReportModel} entries.
 *
 * <p>This is the second consumer the report was generalized for, and it fills exactly the slots
 * the first one leaves empty. An entry names its row by the location the <em>format</em> reports
 * — a file line, a sheet and a row — never the data-row ordinal the reader counted, which a
 * header row and any skipped rows shift away from what the author sees. It carries the column
 * and the text that was refused, because a validation report is a Row / Field / Message table.
 * And a file that could not be read at all belongs to no row, so it fills the file-level slot
 * rather than inventing a row number to hang itself on.
 *
 * <p>The caps are this feeder's own and deliberately larger than a bulk banner's: a report the
 * author has to work through is the surface, not a notice beside one.
 */
final class ImportReports {

    /** Reason groups a validation report renders before it starts counting the rest. */
    private static final int GROUP_CAP = 12;

    /** Entries inside one reason group; the enumerated table below carries the detail. */
    private static final int ENTRY_CAP = 5;

    /**
     * Rows the enumerated table renders. Generous because it is the working surface, and
     * bounded because the parse itself stops recording at 100 — a table that promised more
     * than the machinery keeps would be promising nothing.
     */
    private static final int TABLE_CAP = 50;

    private ImportReports() {
    }

    /** Where a data row sits in the file — the format's answer, asked once per entry. */
    @FunctionalInterface
    interface RowLocator {
        RowReference locate(long row);
    }

    /**
     * The report for one reviewed upload.
     *
     * @param id      the DOM id prefix the region and the group anchors are built from
     * @param locate  the format's own row reference; a text format answers a line, a workbook a
     *                sheet and a row (docs/csv-import.md decision 8)
     */
    static ReportModel of(String id, FileTransferService.ImportReview review, RowLocator locate,
            MessageCatalog catalog, Locale locale) {
        List<String> fileErrors = new ArrayList<>();
        if (review.fileError() != null) {
            fileErrors.add(ViewMessages.text(catalog, locale, "tql.import.fileError",
                    "The file could not be read: {reason}",
                    Map.of("reason", review.fileError())));
        }
        List<ReportModel.Entry> entries = new ArrayList<>();
        for (FileTransferService.RowError error : review.errors()) {
            entries.add(entry(locate, catalog, locale, error));
        }
        return new ReportModel(id, variant(review), summary(review, catalog, locale), fileErrors,
                entries, GROUP_CAP, ENTRY_CAP, TABLE_CAP);
    }

    /**
     * The report a finished import's card carries: the same entries, summarized by what the run
     * did rather than by what it would do. The rejections are whatever the run recorded — a
     * parse refusal names its column, a write refusal names its failure class — so one shape
     * covers both passes, which is the point of the row error carrying them the same way.
     */
    static ReportModel ofTransfer(String id, FileTransferService.TransferStatus status,
            RowLocator locate, MessageCatalog catalog, Locale locale) {
        List<ReportModel.Entry> entries = new ArrayList<>();
        for (FileTransferService.RowError error : status.errors()) {
            entries.add(entry(locate, catalog, locale, error));
        }
        String summary = ViewMessages.text(catalog, locale, "tql.import.imported",
                "{rows} row(s) imported; {rejected} rejected.",
                Map.of("rows", status.rows(), "rejected", status.errors().size()));
        return new ReportModel(id, entries.isEmpty() ? "success" : "warning", summary, List.of(),
                entries, GROUP_CAP, ENTRY_CAP, TABLE_CAP);
    }

    /**
     * One rejection. The reason is the (column, complaint) pair — the value is deliberately not
     * in it, or a hundred bad numbers in one column would be a hundred reasons.
     */
    private static ReportModel.Entry entry(RowLocator locate, MessageCatalog catalog,
            Locale locale, FileTransferService.RowError error) {
        RowReference where = locate.locate(error.row());
        String label = label(where, catalog, locale);
        String reason = error.field() == null
                ? String.valueOf(error.message())
                : error.field() + " — " + error.message();
        return new ReportModel.Entry(error.field() == null ? "row" : error.field(), reason,
                label, null, error.field(), error.value());
    }

    /**
     * How the report refers to a row: the format's own word for it. A text file has lines; a
     * workbook has rows on a sheet, and a named sheet is worth saying because a feed often
     * carries several.
     */
    private static String label(RowReference where, MessageCatalog catalog, Locale locale) {
        if (!where.sheeted()) {
            return ViewMessages.text(catalog, locale, "tql.import.line", "Line {number}",
                    Map.of("number", where.number()));
        }
        if (where.sheet() == null) {
            return ViewMessages.text(catalog, locale, "tql.import.row", "Row {number}",
                    Map.of("number", where.number()));
        }
        return ViewMessages.text(catalog, locale, "tql.import.sheetRow", "{sheet} row {number}",
                Map.of("sheet", where.sheet(), "number", where.number()));
    }

    /**
     * The headline. It leads with what can be committed, because that is the decision in front
     * of the author — and under {@code onError: rollback} a partly-invalid file offers nothing
     * to commit, which the sentence says rather than leaving the missing button to imply it.
     */
    private static String summary(FileTransferService.ImportReview review,
            MessageCatalog catalog, Locale locale) {
        if (review.fileError() != null) {
            return ViewMessages.text(catalog, locale, "tql.import.unreadable",
                    "This file could not be imported.", Map.of());
        }
        if (review.rejected() == 0) {
            return ViewMessages.text(catalog, locale, "tql.import.allValid",
                    "{rows} row(s) ready to import.", Map.of("rows", review.rows()));
        }
        return ViewMessages.text(catalog, locale,
                review.committable() ? "tql.import.partial" : "tql.import.blocked",
                review.committable()
                        ? "{ready} of {rows} row(s) can be imported; {rejected} were rejected."
                        : "{rejected} of {rows} row(s) were rejected, so none can be imported.",
                Map.of("rows", review.rows(), "ready", review.ready(),
                        "rejected", review.rejected()));
    }

    /** Green when everything passed, amber when something can still be committed, red when not. */
    private static String variant(FileTransferService.ImportReview review) {
        if (review.fileError() != null || !review.committable()) {
            return "error";
        }
        return review.rejected() == 0 ? "success" : "warning";
    }
}
