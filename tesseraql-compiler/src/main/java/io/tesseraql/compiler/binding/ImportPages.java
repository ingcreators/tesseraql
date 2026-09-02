package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.template.Templates;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rendering for the transfer sub-routes, which are framework mounts rather than authored ones
 * (docs/csv-import.md decision 6): the status leg and the cancel leg both answer the job card,
 * and neither has a route document to hang a {@code response.html:} on.
 *
 * <p>So they render the shipped pattern directly — which keeps the L2 override working, because
 * the resolver chain looks under the app's own {@code templates/} first either way.
 */
final class ImportPages {

    /** The fragment a poll swaps, and the page a plain navigation lands on. */
    private static final String CARD = "tql/view/job-card";
    private static final String PAGE = "tql/view/job-page";

    private ImportPages() {
    }

    /** The app's catalog over the framework's; the framework's alone outside an app home. */
    static MessageCatalog catalog(Path appHome) {
        return appHome == null
                ? I18nSettings.builtinCatalog()
                : MessageCatalog.live(appHome.resolve("messages"))
                        .withFallback(I18nSettings.builtinCatalog());
    }

    /**
     * Answers the job card, 200. Always 200, including the tombstone: a polling card reads a
     * 4xx as a failure and keeps asking, so an id that is gone has to arrive as an answer.
     *
     * <p>An htmx poll gets the bare card, because that is the unit it swaps. A plain navigation
     * — a no-JS confirm's redirect, or someone refreshing the address by hand — gets the same
     * card inside the app's chrome, so post/redirect/get lands on a page rather than on a
     * floating fragment.
     */
    static void renderCard(Exchange exchange, Path appHome, Map<String, Object> card,
            Locale locale) {
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.setBody(render(exchange, appHome, card, locale,
                "true".equals(exchange.request().header("HX-Request")) ? CARD : PAGE));
    }

    /** The card or the page around it, against the reserved variables the shell reads. */
    static String render(Exchange exchange, Path appHome, Map<String, Object> card, Locale locale,
            String template) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("c", card);
        // The card's Cancel is a POST, so it carries the session's token like every other form
        // the framework renders — and the shell publishes the same token as its meta tag.
        String csrf = exchange.getProperty(TesseraqlProperties.CSRF_TOKEN, String.class);
        if (csrf != null) {
            model.put("_csrf", csrf);
        }
        Path root = appHome == null ? Path.of(".") : appHome;
        // The card is selected out of its file, the page is rendered whole. A fragment file
        // carries a doctype and its own documentation above the fragment, and a swap that
        // shipped those would put a second document inside the page it swapped into.
        return CARD.equals(template)
                ? Templates.render(root, template, model, locale, "card")
                : Templates.render(root, template, model, locale);
    }
}
