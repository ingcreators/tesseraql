package io.tesseraql.compiler.binding;

import io.tesseraql.core.bulk.BulkReportStore;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The browser leg of a bulk endpoint (docs/bulk-report.md decision 6): a grid page's native
 * form post gets its outcome report back on the very page it acted from. Two steps share this
 * class because they share one contract and must not drift.
 *
 * <p>The {@link #bridge} runs before binding: it recognizes the grid page's post (a
 * form-encoded body carrying a decoded {@code ids} selection and a validated {@code _return}),
 * binds the action set from the SELECTION — never from the form's {@code keys}, which on a
 * snapshot list is the whole frozen membership riding along for the page render — and keeps
 * that membership aside for numbering. The {@link #response} runs after the processor: it
 * folds the per-key outcomes into the report shape (one entry per failed key, grouped and
 * bounded when it renders), parks it in the {@link BulkReportStore}, and answers <b>307</b> to
 * the list URL plus the report handle — the browser re-posts the intact form to the list's own
 * POST leg, so a snapshot's frozen membership survives the round trip. An offset or keyset
 * list (no membership in the form) takes the ordinary <b>303</b>: its state lives in the
 * {@code _return} URL, so a fresh GET is the honest re-render. Everything else — a JSON
 * caller, a composite-key view, a missing store — keeps the JSON outcomes contract
 * untouched.
 */
public final class BulkReportRoundTrip {

    /** Exchange property: this request is the grid page's browser post. */
    private static final String BROWSER = "TqlBulkBrowser";
    /** Exchange property: the snapshot membership tokens the form carried, or absent. */
    private static final String MEMBERSHIP = "TqlBulkMembership";
    /** Exchange property: the validated {@code _return} target. */
    private static final String RETURN_TO = "TqlBulkReturn";

    /** The query parameter the redirect carries and the list render picks up. */
    public static final String PARAM = "bulkReport";

    /** How long a parked report outlives its redirect; a refresh may re-read it. */
    private static final long TTL_MILLIS = 15 * 60 * 1000L;

    private BulkReportRoundTrip() {
    }

    /** The pre-binder half; {@code viewKey} is the acting list view's declared key. */
    public static Step bridge(List<String> viewKey) {
        if (viewKey == null || viewKey.size() != 1) {
            // No acting single-key list view: the browser leg does not exist here. Composite
            // keys stop at the bulk boundary (the list-surface slice-8 cut, recorded).
            return exchange -> {
            };
        }
        return exchange -> {
            String contentType = exchange.request().header(Headers.CONTENT_TYPE);
            if (contentType == null
                    || !contentType.contains("application/x-www-form-urlencoded")) {
                return;
            }
            Map<String, List<String>> form = exchange.request().formFields();
            List<String> ids = form.get("ids");
            List<String> returnTo = form.get("_return");
            if (ids == null || ids.isEmpty() || returnTo == null || returnTo.isEmpty()
                    || !io.tesseraql.core.http.BasePaths.isLocal(returnTo.get(0))) {
                return;
            }
            exchange.setProperty(BROWSER, Boolean.TRUE);
            exchange.setProperty(RETURN_TO, returnTo.get(0));
            List<String> membership = form.get("keys");
            if (membership != null && !membership.isEmpty()) {
                exchange.setProperty(MEMBERSHIP, List.copyOf(membership));
            }
            // The action set is the selection. The form's `keys` fields are the snapshot
            // membership — binding them would act on every row of the queue.
            form.put("keys", new ArrayList<>(ids));
            // The rest of the grid form (sort, filters, search state) is presentation the
            // list render needs and the bulk route never declared: dropped here so the
            // mass-assignment guard stays honest. The browser's own body is untouched — the
            // 307 re-post still carries everything the list's render leg reads.
            form.keySet().retainAll(java.util.Set.of("_csrf", "_idempotency", "_return",
                    "keys", "ids", "page", "size"));
        };
    }

    /** The response half, wrapping the route's JSON renderer for every other caller. */
    public static Step response(List<String> viewKey, Step jsonRenderer) {
        return exchange -> {
            BulkReportStore store = exchange.beans()
                    .lookup(TesseraqlProperties.BULK_REPORT_STORE_BEAN, BulkReportStore.class);
            Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                    Principal.class);
            if (!Boolean.TRUE.equals(exchange.getProperty(BROWSER, Boolean.class))
                    || store == null || principal == null) {
                jsonRenderer.process(exchange);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT,
                    Map.of(), Map.class);
            Object bulk = context.get("bulk");
            String returnTo = exchange.getProperty(RETURN_TO, String.class);
            if (!(bulk instanceof Map<?, ?> report) || returnTo == null) {
                jsonRenderer.process(exchange);
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> membership = exchange.getProperty(MEMBERSHIP, List.class);
            String payload = io.tesseraql.yaml.JsonMappers.constrained().writeValueAsString(
                    payload(report, membership, viewKey));
            String handle = store.put(principal.subject(), payload, TTL_MILLIS);
            String target = returnTo + (returnTo.contains("?") ? "&" : "?") + PARAM + "="
                    + handle;
            // The form itself names the acting strategy (docs/bulk-report.md decision 6): a
            // snapshot list's form carried its frozen membership, so the answer is 307 — the
            // browser re-POSTS the intact form to the list route's own page leg, and the
            // membership, sort and filters all survive; a refresh re-posts the idempotent
            // page fetch, the post/redirect/get property in method-preserving form. A form
            // without membership is an offset or keyset list, whose state lives in the
            // `_return` URL — the ordinary 303 GET is the honest re-render there.
            exchange.response().status(membership != null ? 307 : 303);
            exchange.response().header("Location",
                    io.tesseraql.pipeline.BasePath.url(exchange, target));
            exchange.setBody("");
        };
    }

    /**
     * The stored report: totals plus one entry per failed key, complete and ungrouped.
     *
     * <p>Complete, because the display bound belongs to whoever renders it
     * (docs/csv-import.md decision 4). This used to group here and drop everything past the
     * fifth row of a group, so a report could not be re-rendered with a wider bound and could
     * never recover what storing it had already thrown away. The set is bounded anyway: it is
     * one entry per failed key of one submitted selection.
     *
     * <p>Ungrouped, because grouping is a render decision — the reason key, the heading, the
     * caps — and the render owns it for both feeders now.
     */
    private static Map<String, Object> payload(Map<?, ?> report, List<String> membership,
            List<String> viewKey) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("requested", report.get("requested"));
        stored.put("succeeded", report.get("succeeded"));
        stored.put("failed", report.get("failed"));
        List<Map<String, Object>> entries = new ArrayList<>();
        // Position by token, once: the frozen membership is a whole page-set, and every
        // failed key used to scan it.
        Map<String, Integer> positions = new LinkedHashMap<>();
        if (membership != null) {
            for (int i = 0; i < membership.size(); i++) {
                positions.putIfAbsent(membership.get(i), i + 1);
            }
        }
        if (report.get("outcomes") instanceof List<?> outcomes) {
            for (Object candidate : outcomes) {
                if (!(candidate instanceof Map<?, ?> outcome)
                        || Integer.valueOf(200).equals(outcome.get("status"))) {
                    continue;
                }
                String key = String.valueOf(outcome.get("key"));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("reason", outcome.get("guard") != null
                        ? String.valueOf(outcome.get("guard"))
                        : String.valueOf(outcome.get("code")));
                entry.put("message", outcome.get("message"));
                entry.put("key", key);
                String token = io.tesseraql.core.rows.RowTokens.encode(
                        Map.of(viewKey.get(0), key), viewKey);
                entry.put("token", token);
                Integer number = positions.get(token);
                if (number != null) {
                    // Authoritative only on a snapshot list: the posted membership order IS
                    // the frozen order (docs/bulk-report.md decision 4).
                    entry.put("number", number);
                }
                entries.add(entry);
            }
        }
        stored.put("entries", entries);
        return stored;
    }
}
