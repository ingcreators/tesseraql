package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bulk transition (docs/approval-workflow.md, "Bulk transitions"): one action over many
 * documents, run as many actions.
 *
 * <p>A transition was one document per call, so a task inbox approving twenty requisitions made
 * twenty requests and whatever partial-failure reporting the client improvised was the contract.
 * Set-oriented SQL writes already had their answer — an array input and one {@code %for}
 * statement — but the per-document transition pipeline is the thing nothing may bypass:
 * security, guard, task authority, advance, stamps, command, history.
 *
 * <p>So this bypasses none of it. Each key runs the member's own command processor — the very
 * pipeline its single-document endpoint runs, in its own transaction — and the response is a
 * per-key outcome report in the import idiom. A refused or conflicted key does not disturb the
 * others, and an all-or-nothing bulk approve is deliberately not offered: a hundred-document
 * rollback on the ninety-seventh guard is not what an inbox user means by "approve these".
 *
 * <p>An unclassified failure is different. A {@link TqlException} is a refusal the framework
 * understands and can report against its key; anything else means it does not know what
 * happened, and carrying on through the remaining keys would be guessing — so it fails the
 * request, the same line {@link WorkflowDispatchProcessor} draws.
 */
public final class WorkflowBulkProcessor implements Step {

    /** TQL-WORKFLOW-3116: the request names more keys than the bulk ceiling allows (HTTP 400). */
    private static final TqlErrorCode TOO_MANY_KEYS = new TqlErrorCode(TqlDomain.WORKFLOW, 3116);

    private final String workflowId;
    private final String actionId;
    private final Step member;
    private final int maxKeys;

    public WorkflowBulkProcessor(String workflowId, String actionId, Step member, int maxKeys) {
        this.workflowId = workflowId;
        this.actionId = actionId;
        this.member = member;
        this.maxKeys = maxKeys;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context == null) {
            context = new java.util.HashMap<>();
            exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        }
        List<Object> keys = declaredKeys(context);
        if (keys.size() > maxKeys) {
            // Refused whole, before a single key runs: a client past the ceiling should page,
            // and half-applying its request would leave it guessing which half.
            throw TqlException.builder(TOO_MANY_KEYS)
                    .message("Workflow '" + workflowId + "': bulk '" + actionId + "' was given "
                            + keys.size() + " keys, over the " + maxKeys
                            + "-key ceiling (tesseraql.workflow.bulk.maxKeys)")
                    .details(Map.of("keys", keys.size(), "maxKeys", maxKeys))
                    .build();
        }

        // Each key's pipeline mutates the shared context (document, steps, decisions, audit);
        // the next key must see what the first one saw, not what the last one left.
        Map<String, Object> snapshot = new LinkedHashMap<>(context);
        List<Map<String, Object>> outcomes = new ArrayList<>();
        int succeeded = 0;
        for (Object key : keys) {
            String docId = key == null ? null : String.valueOf(key);
            context.clear();
            context.putAll(snapshot);
            // The member pipeline reads its document key from path.key, because that is where
            // its own endpoint puts it. One endpoint, one key path, whichever route arrived.
            context.put("path", Map.of("key", docId == null ? "" : docId));
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("key", docId);
            try {
                member.process(exchange);
                outcome.put("status", 200);
                succeeded++;
            } catch (TqlException refused) {
                outcome.put("status", ErrorResponseRenderer.httpStatus(refused.code()));
                outcome.put("code", refused.code().toString());
                if (refused.details() != null && refused.details().get("code") != null) {
                    // A SQL guard's declared refusal code, under the name the single endpoint
                    // gives it, so a client reads one vocabulary either way.
                    outcome.put("guard", refused.details().get("code"));
                }
                if (refused.details() != null && refused.details().get("message") != null) {
                    // A guard's DECLARED refusal text (the same field the single endpoint's
                    // error fragment surfaces) — never the exception's internal message, which
                    // is not written for end users. The bulk report groups by it
                    // (docs/bulk-report.md decision 1); additive for JSON callers.
                    outcome.put("message", refused.details().get("message"));
                }
            }
            outcomes.add(outcome);
        }

        context.clear();
        context.putAll(snapshot);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("requested", outcomes.size());
        report.put("succeeded", succeeded);
        report.put("failed", outcomes.size() - succeeded);
        report.put("outcomes", outcomes);
        context.put("bulk", report);
    }

    /** The request's {@code keys:} — declared input, so the binder has already typed it. */
    private static List<Object> declaredKeys(Map<String, Object> context) {
        Object params = context.get("params");
        Object declared = params instanceof Map<?, ?> map ? map.get("keys") : null;
        if (declared instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }
}
