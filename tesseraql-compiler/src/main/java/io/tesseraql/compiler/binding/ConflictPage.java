package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.yaml.template.Templates;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The conflict as a page (docs/edit-conflict.md decision 9): what a save posted without
 * JavaScript gets when the record moved under it.
 *
 * <p>Before this, a native form post that failed fell through to the JSON envelope, because the
 * custom-error-page branch is gated on a predicate that refuses every non-GET. So this is the
 * first HTML answer a failing POST has ever had in this framework — narrowly, for one code, which
 * is the right blast radius: the general hole is real and bigger than this campaign.
 *
 * <p>The page echoes the caller's own submitted values as hidden inputs so the overwrite carries
 * what they typed. That is safe for the one reason that matters — the disclosure is to the person
 * who typed it — and it reads them from the parsed form fields rather than the bound body, which
 * has already collapsed a repeated key into a list whose string form is not a value.
 *
 * <p>A multipart save is out of reach: an uploaded part cannot be re-expressed as a hidden input.
 * No declarative form route carries one today, and half-handling it would be worse than saying so.
 */
final class ConflictPage {

    private ConflictPage() {
    }

    /**
     * Renders the page.
     *
     * @param hint       the refusal's own resolved sentence
     * @param reloadHref the record's own page, or null to render no reload link
     */
    static String render(Exchange exchange, Path appHome, String hint, String waiverField,
            String reloadHref, Locale locale) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("hint", hint);
        // The address the browser posted to, used verbatim: it is a wire URL that already
        // carries the application's prefix, so it is emitted without the link builder while
        // everything else on the page goes through it.
        page.put("action", exchange.request().uri());
        page.put("overwriteField", waiverField);
        page.put("reload", reloadHref);
        page.put("fields", echoed(exchange, waiverField));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("c", page);
        // Every template resolves its own URLs against this (docs/base-path.md), and the shell
        // this page composes carries the stylesheet, script and icon links that need it. Without
        // it the 409 page arrives unstyled and its Reload link points outside the application —
        // in a hosted stack, at a different member's address entirely.
        model.put(io.tesseraql.yaml.template.BasePathLinkBuilder.BASE_PATH_VARIABLE,
                io.tesseraql.pipeline.BasePath.of(exchange.beans())
                        + io.tesseraql.pipeline.BasePath.activationSegment(exchange));
        Path root = appHome == null ? Path.of(".") : appHome;
        return Templates.render(root, "tql/view/conflict", model, locale);
    }

    /**
     * The caller's own fields, one hidden input per submitted value.
     *
     * <p>The waiver is dropped: it belongs to the button, and a hidden copy would ride every
     * submit rather than the one press that meant it. The lock is dropped too — the page exists
     * because that value is stale, and sending it back would refuse again.
     */
    private static List<Map<String, String>> echoed(Exchange exchange, String waiverField) {
        List<Map<String, String>> fields = new ArrayList<>();
        exchange.request().formFields().forEach((name, values) -> {
            if (waiverField.equals(name) || io.tesseraql.core.sql.LockBinding.PARAM.equals(name)) {
                return;
            }
            for (String value : values) {
                fields.add(Map.of("name", name, "value", value));
            }
        });
        return fields;
    }
}
