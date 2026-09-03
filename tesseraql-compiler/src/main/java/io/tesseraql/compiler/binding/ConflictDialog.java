package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.yaml.i18n.I18nSettings;
import java.util.Map;

/**
 * The conflict dialog of the kit's {@code edit-conflict} recipe (docs/edit-conflict.md decision
 * 6): the fragment an htmx save receives when the record moved under it, aimed at the shell's
 * shared dialog host via {@code HX-Retarget}. {@code installRemoteDialog} opens it; no behaviour
 * of ours runs, and the recipe's own contract says none should.
 *
 * <p>Two choices, and the overwrite is a <strong>submit button for the page's own form</strong>,
 * associated by the HTML {@code form} attribute and carrying its waiver as its own submit value.
 * That is what keeps the user's typed values out of this fragment entirely, what lets the kit's
 * dirty guard clean on success — it only recognises a request the guarded form itself issued —
 * and what makes the waiver single-shot, because a submit button's value travels only when that
 * button submits. The form's own Save button still sends the stale lock and still refuses.
 *
 * <p>"Keep editing" is the dialog's own dismissal rather than a third server choice, and it is
 * the {@code autofocus} one: {@code showModal()} focuses the first focusable descendant, and the
 * destructive choice must never be the one a reflex Enter commits.
 */
public final class ConflictDialog {

    /**
     * The marker the page-level beforeSwap allowance gates the 409 swap on. Deliberately not a
     * prefix of {@link #HOST} and not contained by it: the allowance is a raw substring test over
     * the whole response body, so a marker the shell's own markup carries would open the gate for
     * every 4xx body that renders the shell.
     */
    public static final String MARKER = "data-tql-conflict-dialog";

    /**
     * The retarget selector. It names a third attribute co-located on the shell's one
     * remote-dialog host: the kit opens a swapped dialog only when its target also matches
     * {@code [data-hc-remote-dialog-root]}, and the unique attribute is what keeps Studio's
     * in-card hosts — earlier in document order — from swallowing this one.
     */
    public static final String HOST = "[data-tql-conflict-host]";

    private ConflictDialog() {
    }

    /**
     * Renders the dialog.
     *
     * @param details    the already-localized error details; the body is {@code conflict.hint},
     *                   the one sentence channel a refusal filling {@code conflict} has
     * @param formId     the id of the form that made the request, from its {@code HX-Trigger}
     *                   header; null renders no overwrite button rather than one pointing at
     *                   nothing
     * @param reloadHref the record's own page, already interpolated and app-relative; null
     *                   renders no reload link
     */
    public static String render(Exchange exchange, I18nSettings i18n, String tag,
            Map<String, Object> details, String formId, String reloadHref) {
        StringBuilder html = new StringBuilder();
        html.append("<dialog class=\"hc-dialog\" ").append(MARKER)
                .append(" aria-labelledby=\"tql-conflict-title\">");
        html.append("<div class=\"hc-dialog__header\">")
                .append("<h2 class=\"hc-dialog__title\" id=\"tql-conflict-title\">")
                .append(text(i18n, tag, "tql.conflict.title")).append("</h2></div>");
        html.append("<div class=\"hc-dialog__body\"><p>").append(body(i18n, tag, details))
                .append("</p></div>");
        html.append("<div class=\"hc-dialog__footer\">");
        // The dismissal first and focused: forms cannot nest, so the native dialog close is a
        // form of its own, and it is what a reflex Enter should reach.
        html.append("<form method=\"dialog\"><button class=\"hc-button\" data-variant=\"ghost\""
                + " autofocus>").append(text(i18n, tag, "tql.conflict.keep"))
                .append("</button></form>");
        if (reloadHref != null) {
            // Base-prefixed here, because this fragment is written as text: the page face gets
            // the same job done by Thymeleaf's own link syntax, and the redirect a successful
            // save takes gets it from the redirect renderer.
            html.append("<a class=\"hc-button\" data-variant=\"ghost\" href=\"")
                    .append(escape(io.tesseraql.pipeline.BasePath.url(exchange, reloadHref)))
                    .append("\">")
                    .append(text(i18n, tag, "tql.conflict.reload")).append("</a>");
        }
        if (formId != null && !formId.isBlank()) {
            html.append("<button class=\"hc-button\" data-variant=\"primary\" type=\"submit\""
                    + " form=\"").append(escape(formId)).append("\" name=\"")
                    .append(escape(waiverField(details))).append("\" value=\"1\">")
                    .append(text(i18n, tag, "tql.conflict.overwrite")).append("</button>");
        }
        html.append("</div></dialog>");
        return html.toString();
    }

    /**
     * The sentence: the refusal's own resolved hint, which the renderer localized before this
     * ran, falling back to the stale-write text when a caller built the details by hand.
     */
    private static String body(I18nSettings i18n, String tag, Map<String, Object> details) {
        if (details.get("conflict") instanceof Map<?, ?> conflict && conflict.get("hint") != null) {
            return escape(String.valueOf(conflict.get("hint")));
        }
        return text(i18n, tag, "tql.conflict.stale");
    }

    /** The waiver's field name, as the envelope published it. */
    private static String waiverField(Map<String, Object> details) {
        if (details.get("lock") instanceof Map<?, ?> lock && lock.get("overwriteField") != null) {
            return String.valueOf(lock.get("overwriteField"));
        }
        return LockBinder.OVERWRITE_FIELD;
    }

    private static String text(I18nSettings i18n, String tag, String key) {
        String resolved = i18n.catalog().resolve(tag, i18n.defaultTag(), key);
        return escape(resolved != null ? resolved : key);
    }

    private static String escape(String value) {
        return io.tesseraql.core.text.Escapes.html(value);
    }
}
