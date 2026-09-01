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
 * folds the per-key outcomes into the report shape (reason groups bounded by construction),
 * parks it in the {@link BulkReportStore}, and answers <b>307</b> to the list URL plus the
 * report handle — the browser re-posts the intact form to the list's own POST leg, so a
 * snapshot's frozen membership survives the round trip. Everything else — a JSON caller, a
 * composite-key view, a missing store — keeps the JSON outcomes contract untouched.
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

    /** Report entries rendered per reason group; the grid's row marks carry the rest. */
    private static final int GROUP_CAP = 5;

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
            // 307, not 303: the browser re-POSTS the intact form to the list route's own
            // page leg (a snapshot's membership, sort and filters all live in that body),
            // and the bulk action itself is behind us — a refresh re-posts the idempotent
            // page fetch, which is the post/redirect/get property in method-preserving form.
            exchange.response().status(307);
            exchange.response().header("Location",
                    io.tesseraql.pipeline.BasePath.url(exchange, target));
            exchange.setBody("");
        };
    }

    /** The stored report: totals, the full failed set for marks, bounded reason groups. */
    private static Map<String, Object> payload(Map<?, ?> report, List<String> membership,
            List<String> viewKey) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("requested", report.get("requested"));
        stored.put("succeeded", report.get("succeeded"));
        stored.put("failed", report.get("failed"));
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        // token -> index of its reason group: what the list render needs to mark a failed
        // row (data-attention + aria-describedby) and re-check the retry set.
        Map<String, Integer> tokenGroups = new LinkedHashMap<>();
        if (report.get("outcomes") instanceof List<?> outcomes) {
            for (Object entry : outcomes) {
                if (!(entry instanceof Map<?, ?> outcome)
                        || Integer.valueOf(200).equals(outcome.get("status"))) {
                    continue;
                }
                String key = String.valueOf(outcome.get("key"));
                String token = io.tesseraql.core.rows.RowTokens.encode(
                        Map.of(viewKey.get(0), key), viewKey);
                String reason = outcome.get("guard") != null
                        ? String.valueOf(outcome.get("guard"))
                        : String.valueOf(outcome.get("code"));
                Map<String, Object> group = groups.computeIfAbsent(reason, r -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("reason", r);
                    g.put("code", outcome.get("code"));
                    g.put("message", outcome.get("message"));
                    g.put("count", 0);
                    g.put("rows", new ArrayList<Map<String, Object>>());
                    return g;
                });
                tokenGroups.put(token, new ArrayList<>(groups.keySet()).indexOf(reason));
                group.put("count", (Integer) group.get("count") + 1);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) group.get("rows");
                if (rows.size() < GROUP_CAP) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", key);
                    row.put("token", token);
                    int number = membership == null ? -1 : membership.indexOf(token);
                    if (number >= 0) {
                        // Authoritative only on a snapshot list: the posted membership order
                        // IS the frozen order (docs/bulk-report.md decision 4).
                        row.put("number", number + 1);
                    }
                    rows.add(row);
                }
            }
        }
        stored.put("groups", new ArrayList<>(groups.values()));
        stored.put("tokenGroups", tokenGroups);
        return stored;
    }
}
