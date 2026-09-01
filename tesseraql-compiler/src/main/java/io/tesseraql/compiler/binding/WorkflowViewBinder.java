package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.model.TransitionSpec;
import io.tesseraql.yaml.model.WorkflowDefinition;
import io.tesseraql.yaml.workflow.TransitionExecutor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the workflow facts a {@code workflow:}-declaring detail view renders
 * (docs/workflow-surface.md decision 2): the document's current state and, per declared
 * transition out of it, three-valued legality — a transition the principal's policy refuses is
 * absent, one whose expression guard is false is disabled with the declared reason, and one
 * whose guard needs the transition's own connection (a SQL file, or a {@code decision.*} read)
 * renders enabled, because the executor's refusal is the truth a render-time peek must not
 * fake. Task authority gates the whole action set, mirroring the executor: a document with open
 * tasks acts only for someone who holds one.
 *
 * <p>Facts only — the display model (labels, messages, URLs) is {@link ViewBinding}'s, built
 * from the {@code workflow} context entry this step writes. Runs after the route's SQL step
 * (the row is the evaluation subject) and before the renderer; beans resolve per request off
 * the exchange, the {@code CatalogBinder} pattern.
 */
public final class WorkflowViewBinder implements Step {

    /**
     * TQL-VIEW-3328: the detail row is missing the workflow's key column or, in app mode, its
     * state column — the region cannot render truthfully without them, so the page refuses
     * loudly instead of showing a lifecycle that may be wrong (docs/workflow-surface.md
     * decision 1).
     */
    static final TqlErrorCode MISSING_COLUMN = new TqlErrorCode(TqlDomain.VIEW, 3328);

    private final String source;
    private final WorkflowDefinition def;
    private final boolean managed;
    private final String datasourceName;
    private final List<TransitionSpec> specs;
    private final List<TransitionExecutor.CompiledTransition> compiled;

    public WorkflowViewBinder(String source, WorkflowDefinition def, boolean managed,
            String datasourceName, List<TransitionSpec> specs,
            List<TransitionExecutor.CompiledTransition> compiled) {
        this.source = source;
        this.def = def;
        this.managed = managed;
        this.datasourceName = datasourceName;
        this.specs = List.copyOf(specs);
        this.compiled = List.copyOf(compiled);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Object raw = exchange.getProperty(TesseraqlProperties.CONTEXT);
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> context = (Map<String, Object>) raw;
        Map<String, Object> row = firstRow(context.get(source));
        if (row == null) {
            // No row, no region: the view's own not-found state is the page's answer.
            return;
        }
        Object docId = row.get(def.document().key());
        if (docId == null) {
            throw missing(def.document().key(), "key");
        }
        String current = currentState(exchange, row, String.valueOf(docId));
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                Principal.class);
        io.tesseraql.security.policy.PolicyEngine policies = exchange.beans().lookup(
                TesseraqlProperties.POLICY_ENGINE_BEAN,
                io.tesseraql.security.policy.PolicyEngine.class);
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            TransitionSpec spec = specs.get(i);
            if (!java.util.Objects.equals(spec.from(), current)) {
                continue;
            }
            // The policy check is the render-time half of the route gate the synthesized
            // transition carries; the same fail-safe as the form surface — no engine bound
            // means nothing renders permitted.
            io.tesseraql.yaml.model.SecuritySpec security = spec.security() != null
                    ? spec.security()
                    : def.security();
            String policy = security == null ? null : security.policy();
            if (policy != null && (policies == null || !policies.permits(policy, principal))) {
                continue;
            }
            transitions.add(transitionFacts(spec, compiled.get(i), context, row));
        }
        if (!transitions.isEmpty() && blockedByTasks(exchange, principal,
                String.valueOf(docId))) {
            for (Map<String, Object> transition : transitions) {
                transition.put("enabled", false);
                transition.put("blocked", true);
            }
        }
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("id", def.id());
        workflow.put("state", current);
        workflow.put("docId", String.valueOf(docId));
        workflow.put("transitions", transitions);
        context.put("workflow", workflow);
    }

    /** One transition's facts: enabled, or disabled with the guard's declared reason. */
    private Map<String, Object> transitionFacts(TransitionSpec spec,
            TransitionExecutor.CompiledTransition transition, Map<String, Object> context,
            Map<String, Object> row) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", spec.id());
        boolean terminal = def.states().stream()
                .anyMatch(state -> state.id().equals(spec.to()) && state.isTerminal());
        facts.put("terminal", terminal);
        boolean enabled = true;
        if (transition.guard() != null) {
            // The executor's own evaluation shape: the request context with the loaded row
            // as `document`. A guard that reaches past what a read has (decision.*, task.*)
            // throws here and renders enabled — indeterminate is not false.
            Map<String, Object> guardContext = new LinkedHashMap<>(context);
            guardContext.put("document", row);
            try {
                if (!transition.guard().evalBoolean(new EvaluationContext(guardContext))) {
                    enabled = false;
                    facts.put("reason", transition.guardMessage());
                }
            } catch (RuntimeException indeterminate) {
                // Render-time cannot answer; the executor will.
            }
        }
        facts.put("enabled", enabled);
        io.tesseraql.yaml.model.JoinSpec join = spec.joinOrNull();
        if (join != null) {
            long done = join.stamps().stream().filter(column -> row.get(column) != null)
                    .count();
            facts.put("joinDone", done);
            facts.put("joinTotal", join.stamps().size());
        }
        return facts;
    }

    /** The document's current state: the row's column in app mode, the instance in managed. */
    private String currentState(Exchange exchange, Map<String, Object> row, String docId)
            throws SQLException {
        if (!managed) {
            if (!row.containsKey(def.document().stateColumn())) {
                throw missing(def.document().stateColumn(), "state");
            }
            Object state = row.get(def.document().stateColumn());
            return state == null ? def.initial() : String.valueOf(state);
        }
        io.tesseraql.core.workflow.WorkflowStore store = exchange.beans().lookup(
                TesseraqlProperties.WORKFLOW_STORE_BEAN,
                io.tesseraql.core.workflow.WorkflowStore.class);
        if (store == null) {
            return def.initial();
        }
        javax.sql.DataSource dataSource = io.tesseraql.pipeline.tenant.TenantRouting
                .dataSource(exchange, datasourceName);
        try (Connection connection = dataSource.getConnection()) {
            String state = store.currentState(connection, def.document().type(), docId);
            return state == null ? def.initial() : state;
        }
    }

    /** The executor's task-authority gate, read-only: open tasks the caller holds none of. */
    private boolean blockedByTasks(Exchange exchange, Principal principal, String docId)
            throws SQLException {
        io.tesseraql.core.workflow.WorkflowTaskStore tasks = exchange.beans().lookup(
                TesseraqlProperties.WORKFLOW_TASK_STORE_BEAN,
                io.tesseraql.core.workflow.WorkflowTaskStore.class);
        if (tasks == null) {
            return false;
        }
        javax.sql.DataSource dataSource = io.tesseraql.pipeline.tenant.TenantRouting
                .dataSource(exchange, datasourceName);
        String subject = principal == null ? null : principal.subject();
        List<String> groups = principal == null ? List.of() : principal.groups();
        try (Connection connection = dataSource.getConnection()) {
            return tasks.hasOpenTasks(connection, def.document().type(), docId)
                    && !tasks.canAct(connection, def.document().type(), docId, subject, groups);
        }
    }

    private TqlException missing(String column, String role) {
        return TqlException.builder(MISSING_COLUMN)
                .message("Workflow '" + def.id() + "': the detail row has no '" + column
                        + "' column — the view's SQL must select the document's " + role
                        + " column for the transitions region to render truthfully"
                        + " (docs/workflow-surface.md)")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(Object data) {
        if (data instanceof Map<?, ?> result
                && result.get("rows") instanceof List<?> rows
                && !rows.isEmpty()
                && rows.get(0) instanceof Map) {
            return (Map<String, Object>) rows.get(0);
        }
        return null;
    }
}
