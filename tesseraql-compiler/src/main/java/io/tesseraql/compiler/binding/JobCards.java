package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The model behind {@code tql/view/job-card.html} (docs/csv-import.md decision 6): one
 * asynchronous transfer, in whichever of five states it is in.
 *
 * <p>The stop condition is the absence of a trigger, not a flag the client interprets: a
 * terminal card is built without {@code hx-trigger}, so it simply stops asking. The cadence a
 * running card writes is the server's too — tight while a small import is likely still going,
 * backed off once it plainly is not — because a client-side backoff would be a second policy
 * for something only this side knows.
 *
 * <p>Five states, and the fifth is why this exists rather than the JSON: an unknown transfer id
 * is a {@code 404} envelope on the API, which is right there and wrong for a poller, because a
 * card that receives an error keeps polling an error. Staleness is a state, so it answers a
 * tombstone that carries no trigger and stops.
 */
final class JobCards {

    /** How often a running card asks again, and the immediate first ask on insertion. */
    private static final String RUNNING_TRIGGER = "load, every 2s";

    /**
     * The cadence a long run backs off to. A run still going after this many rows is not one a
     * two-second question will catch the end of, and the poll costs a query per card per tick.
     */
    private static final long BACKOFF_ROWS = 5_000;
    private static final String BACKOFF_TRIGGER = "load, every 10s";

    private JobCards() {
    }

    /** The card for a transfer that still exists, in whatever state it reached. */
    static Map<String, Object> of(FileTransferService.TransferStatus status, String statusUrl,
            String cancelUrl, ImportReports.RowLocator locate, MessageCatalog catalog,
            Locale locale) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", "tql-job-" + status.transferId());
        card.put("poll", statusUrl);
        boolean running = "RUNNING".equals(status.status());
        card.put("state", state(status));
        card.put("trigger", running
                ? (status.rows() >= BACKOFF_ROWS ? BACKOFF_TRIGGER : RUNNING_TRIGGER)
                : null);
        card.put("cancel", running ? cancelUrl : null);
        card.put("variant", variant(status));
        card.put("label", ViewMessages.text(catalog, locale, "tql.job." + state(status),
                defaultLabel(state(status))));
        card.put("progress", progress(status, catalog, locale));
        // A determinate bar only when the total is known — a reviewed import parsed the file
        // already. Without it the line counts up and no bar pretends to a fraction.
        card.put("max", status.expectedRows());
        card.put("value", status.rows());
        boolean export = "EXPORT".equals(status.direction());
        card.put("file", export && "COMPLETED".equals(status.status())
                ? statusUrl + "/file"
                : null);
        card.put("report", export || status.errors().isEmpty()
                ? null
                : ImportReports.ofTransfer("tql-job-" + status.transferId(), status, locate,
                        catalog, locale).render(catalog, locale).model());
        card.put("failure", failure(status, catalog, locale));
        return card;
    }

    /**
     * The tombstone: an id this runtime does not know, either because it never existed or
     * because the retention sweep reclaimed it. <b>200</b>, so a polling card reads it as an
     * answer, and no trigger, so it is the last answer it asks for.
     */
    static Map<String, Object> tombstone(String transferId, MessageCatalog catalog,
            Locale locale) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", "tql-job-" + transferId);
        card.put("state", "expired");
        // The kit's badge has four variants and none of them is "gone"; info is the
        // quiet one, and a tombstone is a statement of fact rather than a problem.
        card.put("variant", "info");
        card.put("label", ViewMessages.text(catalog, locale, "tql.job.expired", "Expired"));
        card.put("progress", ViewMessages.text(catalog, locale, "tql.job.expiredBody",
                "This job is no longer available."));
        return card;
    }

    /** The contract's dialect-neutral state name, which is also this card's message key stem. */
    private static String state(FileTransferService.TransferStatus status) {
        return switch (status.status()) {
            case "COMPLETED" -> "done";
            case "FAILED" -> "failed";
            case "STOPPED" -> "cancelled";
            case "RUNNING" -> "running";
            default -> "expired";
        };
    }

    private static String defaultLabel(String state) {
        return switch (state) {
            case "done" -> "Done";
            case "failed" -> "Failed";
            case "cancelled" -> "Cancelled";
            case "running" -> "Running";
            default -> "Expired";
        };
    }

    private static String variant(FileTransferService.TransferStatus status) {
        return switch (state(status)) {
            case "done" -> "success";
            case "failed" -> "error";
            case "cancelled" -> "warning";
            default -> "info";
        };
    }

    /** "12 of 30 rows" while the total is known, "12 rows" while it is not. */
    private static String progress(FileTransferService.TransferStatus status,
            MessageCatalog catalog, Locale locale) {
        if (status.expectedRows() != null) {
            return ViewMessages.text(catalog, locale, "tql.job.progressOf",
                    "{rows} of {total} rows",
                    Map.of("rows", status.rows(), "total", status.expectedRows()));
        }
        return ViewMessages.text(catalog, locale, "tql.job.progress", "{rows} rows",
                Map.of("rows", status.rows()));
    }

    /**
     * What a run that did not finish cleanly leaves behind. It says whether anything was
     * written, because that is the question the author actually has, and the two answers are
     * structurally different outcomes rather than shades of one.
     */
    private static String failure(FileTransferService.TransferStatus status,
            MessageCatalog catalog, Locale locale) {
        return switch (state(status)) {
            case "failed" -> ViewMessages.text(catalog, locale, "tql.job.failedBody",
                    "Nothing was written. {rejected} row(s) were rejected.",
                    Map.of("rejected", status.errors().size()));
            case "cancelled" -> ViewMessages.text(catalog, locale, "tql.job.cancelledBody",
                    "Cancelled before it finished; nothing was written.");
            default -> null;
        };
    }
}
