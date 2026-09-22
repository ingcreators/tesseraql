package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The job card for a surface outside this package (docs/job-inbox.md decision 5): the bundled
 * "My exports" page renders the caller's own exports as the card the status poll answers with
 * two seconds later — one markup source, so a card on that page and a card on the grid page
 * cannot drift. An export carries no row report, so the card needs no row locator here.
 *
 * <p>The URLs are wire URLs: the caller prefixes them, because the card emits them verbatim
 * ({@code tql/view/job-card.html}) and a link expression around them would prefix twice.
 */
public final class TransferCards {

    private TransferCards() {
    }

    /** The application's catalog over the framework's, as the transfer pages read it. */
    public static MessageCatalog catalog(Path appHome) {
        return ImportPages.catalog(appHome);
    }

    /**
     * The card of one export in whatever state it reached: running polls {@code statusUrl} on
     * the server's cadence and offers Cancel at {@code cancelUrl}; done downloads from the
     * status URL's file leg; a reclaimed file says expired; failed names the code's sentence.
     */
    public static Map<String, Object> ofExport(FileTransferService.TransferStatus status,
            String statusUrl, String cancelUrl, MessageCatalog catalog, Locale locale) {
        return JobCards.of(status, statusUrl, cancelUrl, row -> null, catalog, locale);
    }
}
