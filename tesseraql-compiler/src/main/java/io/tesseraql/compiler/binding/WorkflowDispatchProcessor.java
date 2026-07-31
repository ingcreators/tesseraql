package io.tesseraql.compiler.binding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;

/**
 * The one-action dispatch (docs/workflow-expressiveness.md slice 3): tries its member
 * transitions — through the internal {@code direct:<workflow>.<transition>.attempt}
 * shadow routes, which run the same pipeline as the members' REST endpoints (rest-dsl
 * inlining removes the REST routes' own direct consumers, so the shadows exist to be
 * sent to) — in declaration order, and adopts the first outcome that is
 * not a wrong-state ({@code TQL-WORKFLOW-3201}) or guard ({@code 3202}) refusal. Each
 * attempt runs the member's own full pipeline (security, decide, guard, advance, scoped
 * command, tasks, history) in its own transaction, so a refused attempt leaves nothing
 * behind and correctness never depends on the selector: a raced state change simply
 * surfaces as the member's own conflict. Security stays deny-by-default per member — a
 * 403 is an outcome, never a fall-through.
 *
 * <p>No member holding answers {@code 422} with the attempted transitions and each one's
 * refusal, so the caller sees the whole picture instead of the last member's complaint.
 */
public final class WorkflowDispatchProcessor implements Processor {

    private final String workflowId;
    private final String dispatchId;
    private final List<String> members;
    private volatile ProducerTemplate template;

    public WorkflowDispatchProcessor(String workflowId, String dispatchId,
            List<String> members) {
        this.workflowId = workflowId;
        this.dispatchId = dispatchId;
        this.members = List.copyOf(members);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (template == null) {
            template = exchange.getContext().createProducerTemplate();
        }
        // Materialize the (typically empty) request body once: a platform-http stream can
        // only be read by one attempt, and a second read blocks.
        String requestBody = exchange.getMessage().getBody(String.class);
        exchange.getMessage().setBody(requestBody);
        List<Map<String, Object>> attempted = new ArrayList<>();
        for (String member : members) {
            Exchange attempt = exchange.copy();
            attempt.getMessage().setBody(requestBody);
            template.send("direct:" + workflowId + "." + member + ".attempt", attempt);
            Integer status = attempt.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE,
                    Integer.class);
            String body = attempt.getMessage().getBody(String.class);
            boolean fellThrough = status != null && (status == 409 || status == 422)
                    && body != null && (body.contains("TQL-WORKFLOW-3201")
                            || body.contains("TQL-WORKFLOW-3202"));
            if (!fellThrough
                    && attempt.getException() instanceof io.tesseraql.core.error.TqlException tql
                    && (tql.code().toString().equals("TQL-WORKFLOW-3201")
                            || tql.code().toString().equals("TQL-WORKFLOW-3202"))) {
                fellThrough = true;
                status = tql.code().toString().endsWith("3201") ? 409 : 422;
            }
            if (!fellThrough && attempt.getException() != null) {
                // A non-selectable failure: rethrow so the standard error path renders it.
                throw attempt.getException();
            }
            if (!fellThrough) {
                // Success or a non-selectable failure (403, 3204, 500): the member's
                // outcome is the dispatch's outcome, headers and all.
                exchange.setMessage(attempt.getMessage());
                return;
            }
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("transition", member);
            refusal.put("status", status);
            attempted.add(refusal);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "TQL-WORKFLOW-3202");
        error.put("message", "Unprocessable Entity");
        error.put("dispatch", dispatchId);
        error.put("attempted", attempted);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 422);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("error", error)));
    }
}
