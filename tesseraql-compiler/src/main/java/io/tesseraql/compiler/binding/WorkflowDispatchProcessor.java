package io.tesseraql.compiler.binding;

import io.tesseraql.core.decision.DecisionTables;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.AmbientBinds;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.TenantRouting;
import io.tesseraql.yaml.workflow.TransitionExecutor;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The one-action dispatch, engine-level (docs/transition-engine.md track B): tries its
 * member transitions in declaration order by invoking each member's own command
 * processor — the same full pipeline (decide, guard, advance, stamps, scoped command,
 * tasks, history, notify) the member's REST endpoint runs, each attempt in its own
 * transaction, so a refused attempt leaves nothing behind and correctness never depends
 * on the selector: a raced state change surfaces as the member's own conflict. The
 * dispatch route carries the members' shared security spec (the
 * {@code TQL-WORKFLOW-3112} lint guarantees one audience), so a {@code 403} is an
 * outcome, never a fall-through.
 *
 * <p>Fall-through is typed: a member refusing with {@code TQL-WORKFLOW-3201}
 * (wrong state) or {@code 3202} (guard) is caught as the exception it threw — never
 * matched against a rendered HTTP body — and its refusal (code, declared guard code)
 * joins the {@code attempted} list. No member holding throws {@code 3202} carrying
 * {@code dispatch} and {@code attempted}, so the caller sees the whole picture. The
 * winner's id rides the response as {@code transition} (the {@code dispatch.transition}
 * context path).
 *
 * <p>A dispatch-level {@code decide:} evaluates once, before the loop, against the
 * loaded document; members that declare no {@code decide:} of their own inherit the
 * results as {@code decision.*}.
 */
public final class WorkflowDispatchProcessor implements Step {

    /** TQL-WORKFLOW-3202: no dispatch member transition holds (HTTP 422). */
    private static final TqlErrorCode NONE_HELD = new TqlErrorCode(TqlDomain.WORKFLOW, 3202);

    /** A member transition and the command processor running its full pipeline. */
    public record Member(String id, Step processor) {
    }

    private final String workflowId;
    private final String dispatchId;
    private final List<Member> members;
    private final DecisionTables decisions;
    private final String table;
    private final String keyColumn;
    private final String dialect;
    private final String datasourceName;
    private final int sqlTimeoutSeconds;

    public WorkflowDispatchProcessor(String workflowId, String dispatchId, List<Member> members,
            DecisionTables decisions, String table, String keyColumn, String dialect,
            String datasourceName, int sqlTimeoutSeconds) {
        this.workflowId = workflowId;
        this.dispatchId = dispatchId;
        this.members = List.copyOf(members);
        this.decisions = decisions;
        this.table = table;
        this.keyColumn = keyColumn;
        this.dialect = dialect;
        this.datasourceName = datasourceName;
        this.sqlTimeoutSeconds = sqlTimeoutSeconds;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT,
                Map.class);
        if (context == null) {
            context = new java.util.HashMap<>();
            exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        }
        // The dispatch-level decide: one evaluation for the whole selection, after the
        // document binds (the wiring may read document.*), on a short read-only
        // connection — each attempt's own transaction re-reads what it advances.
        if (!decisions.isEmpty()) {
            DataSource dataSource = TenantRouting.dataSource(exchange, datasourceName);
            try (Connection connection = dataSource.getConnection()) {
                EvaluationContext evaluation = new EvaluationContext(context);
                Object keyValue = evaluation.resolve(List.of("path", "key"));
                String docId = keyValue == null ? null : String.valueOf(keyValue);
                Map<String, Object> decideContext = new LinkedHashMap<>(context);
                decideContext.put("document", TransitionExecutor.loadDocument(connection,
                        table, keyColumn, dialect, docId, sqlTimeoutSeconds));
                context.put(AmbientBinds.DECISION,
                        decisions.evaluate(decideContext, connection, sqlTimeoutSeconds));
            }
        }
        // Attempts mutate the shared context (document, steps, audit, their own
        // decisions); a refused attempt restores this snapshot so the next member sees
        // what the first one saw.
        Map<String, Object> snapshot = new LinkedHashMap<>(context);
        List<Map<String, Object>> attempted = new ArrayList<>();
        for (Member member : members) {
            try {
                member.processor().process(exchange);
                context.put("dispatch", Map.of("transition", member.id()));
                return;
            } catch (Exception failure) {
                String code = failure instanceof TqlException tql ? tql.code().toString() : null;
                boolean fellThrough = "TQL-WORKFLOW-3201".equals(code)
                        || "TQL-WORKFLOW-3202".equals(code);
                if (!fellThrough) {
                    // A non-selectable outcome (403, 3204, 500): the member's failure is
                    // the dispatch's failure, rendered by the standard error path.
                    throw failure;
                }
                TqlException refused = (TqlException) failure;
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("transition", member.id());
                refusal.put("status", code.endsWith("3201") ? 409 : 422);
                refusal.put("code", code);
                if (refused.details() != null && refused.details().get("code") != null) {
                    // A SQL guard's declared refusal code (`details.code`) keeps the
                    // `guard` name here: the entry's `code` is the registry code.
                    refusal.put("guard", refused.details().get("code"));
                }
                attempted.add(refusal);
                context.clear();
                context.putAll(snapshot);
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dispatch", dispatchId);
        details.put("attempted", attempted);
        throw TqlException.builder(NONE_HELD)
                .message("Workflow '" + workflowId + "': dispatch '" + dispatchId
                        + "' found no member transition that holds")
                .details(details)
                .build();
    }
}
