package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.DeadlineSpec;
import io.tesseraql.yaml.model.StateSpec;
import io.tesseraql.yaml.model.TransitionSpec;
import io.tesseraql.yaml.model.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflows: the workflow documents, the document-type literals routes bind,
 * and workflow configuration.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class WorkflowRules implements LintRule {

    private static final String INVALID_WORKFLOW_MODE = "TQL-WORKFLOW-3110";

    private static final String UNKNOWN_DOC_TYPE_LITERAL = "TQL-WORKFLOW-3114";

    private static final String UNKNOWN_STATE_LITERAL = "TQL-WORKFLOW-3115";

    private static final String UNDECLARED_STATE = "TQL-WORKFLOW-3101";

    private static final String INVALID_INITIAL_STATE = "TQL-WORKFLOW-3102";

    private static final String MISSING_TRANSITION_REFERENCE = "TQL-WORKFLOW-3104";

    private static final String INVALID_DISPATCH = "TQL-WORKFLOW-3112";

    private static final String UNREACHABLE_DISPATCH_MEMBER = "TQL-WORKFLOW-3113";

    private static final String STATE_TRANSITION_DEAD_END = "TQL-WORKFLOW-3105";

    private static final String ESCALATION_FROM_WRONG_STATE = "TQL-WORKFLOW-3107";

    private static final String INVALID_STAMP = "TQL-WORKFLOW-3111";

    private static final String INVALID_GUARD_DECLARATION = "TQL-WORKFLOW-3108";

    private static final String GUARD_FILE_WRITES = "TQL-WORKFLOW-3109";

    private static final String INVALID_GUARD_EXPRESSION = "TQL-WORKFLOW-3103";

    private static final String INCOMPLETE_WORKFLOW_DOCUMENT = "TQL-WORKFLOW-3106";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        lintWorkflows(appHome, manifest, findings);
        lintDocTypeLiterals(appHome, manifest, findings);
        lintWorkflowConfig(manifest.config(), findings);
    }

    /** Validates approval-workflow configuration (roadmap Phase 28): a known {@code mode}. */
    void lintWorkflowConfig(AppConfig config, List<LintFinding> findings) {
        String mode = config.getString("tesseraql.workflow.mode").orElse(null);
        if (mode != null && !"managed".equalsIgnoreCase(mode) && !"app".equalsIgnoreCase(mode)) {
            findings.add(new LintFinding(INVALID_WORKFLOW_MODE, ERROR, "config",
                    "tesseraql.workflow.mode must be 'managed' or 'app', not '" + mode + "'"));
        }
    }

    /** The roots a transition guard may reference (roadmap Phase 28); {@code decision} covers
     * the transition's own {@code decide:} outputs (docs/decision-tables.md). */
    private static final Set<String> GUARD_ROOTS = Set.of("document", "task", "principal",
            "decision");

    /**
     * Lints approval workflows (roadmap Phase 28): each workflow's states and transitions are
     * well-formed (no undeclared/unreachable states, no dead ends), guards are valid whitelist
     * expressions over the allowed roots, referenced files exist, and the declared mode matches the
     * document fields it needs.
     */
    void lintWorkflows(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (WorkflowFile workflow : manifest.workflows()) {
            lintWorkflow(appHome, manifest.config(), workflow, findings);
        }
    }

    private static final java.util.regex.Pattern DOC_TYPE_LITERAL = java.util.regex.Pattern
            .compile("\\bdoc_type\\s*(?:=|in\\s*\\()\\s*'([^']*)'",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern CURRENT_STATE_LITERAL = java.util.regex.Pattern
            .compile("\\bcurrent_state\\s*(?:=|in\\s*\\()\\s*'([^']*)'",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The managed-table literal lints (docs/workflow-expressiveness.md slice 4 and
     * docs/transition-engine.md track D): in SQL that references the managed
     * {@code tql_workflow_instance} table, a string literal compared to {@code doc_type}
     * must name a declared workflow {@code document.type} ({@code TQL-WORKFLOW-3114}) and
     * one compared to {@code current_state} must name a declared state
     * ({@code TQL-WORKFLOW-3115}) — either typo otherwise survives to runtime as an
     * always-empty join. When the file pins exactly one declared document type, its
     * {@code current_state} literals narrow to that workflow's states; otherwise the
     * union of all declared states applies. SQL that never mentions the managed table is
     * skipped, so an application's own columns stay out of scope.
     */
    void lintDocTypeLiterals(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        Set<String> declared = new LinkedHashSet<>();
        Map<String, Set<String>> statesByType = new LinkedHashMap<>();
        Set<String> allStates = new LinkedHashSet<>();
        for (WorkflowFile workflow : manifest.workflows()) {
            WorkflowDefinition def = workflow.definition();
            if (def.document() == null || def.document().type() == null) {
                continue;
            }
            declared.add(def.document().type());
            Set<String> states = new LinkedHashSet<>();
            for (StateSpec state : def.states()) {
                if (state.id() != null) {
                    states.add(state.id());
                }
            }
            statesByType.merge(def.document().type(), states, (a, b) -> {
                a.addAll(b);
                return a;
            });
            allStates.addAll(states);
        }
        if (declared.isEmpty()) {
            return;
        }
        for (String root : List.of("web", "workflow", "rules", "scope")) {
            Path dir = appHome.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file : files
                        .filter(f -> f.getFileName().toString().endsWith(".sql")).toList()) {
                    String sql = context.content(file);
                    if (sql == null) {
                        continue;
                    }
                    if (!sql.contains("tql_workflow_instance")) {
                        continue;
                    }
                    Set<String> pinnedTypes = new LinkedHashSet<>();
                    java.util.regex.Matcher literals = DOC_TYPE_LITERAL.matcher(sql);
                    while (literals.find()) {
                        String literal = literals.group(1);
                        if (declared.contains(literal)) {
                            pinnedTypes.add(literal);
                        } else {
                            findings.add(new LintFinding(UNKNOWN_DOC_TYPE_LITERAL, WARNING,
                                    LintSupport.relative(appHome, file),
                                    "doc_type literal '" + literal
                                            + "' names no declared workflow document type"
                                            + " (declared: " + declared + ")"));
                        }
                    }
                    // The narrowing: one pinned type means the file's states are that
                    // workflow's; anything else falls back to the union.
                    Set<String> states = pinnedTypes.size() == 1
                            ? statesByType.get(pinnedTypes.iterator().next())
                            : allStates;
                    java.util.regex.Matcher stateLiterals = CURRENT_STATE_LITERAL.matcher(sql);
                    while (stateLiterals.find()) {
                        String literal = stateLiterals.group(1);
                        if (!states.contains(literal)) {
                            findings.add(new LintFinding(UNKNOWN_STATE_LITERAL, WARNING,
                                    LintSupport.relative(appHome, file),
                                    "current_state literal '" + literal
                                            + "' names no declared workflow state"
                                            + (pinnedTypes.size() == 1
                                                    ? " of document type '"
                                                            + pinnedTypes.iterator().next()
                                                            + "'"
                                                    : "")
                                            + " (declared: " + states + ")"));
                        }
                    }
                }
            } catch (java.io.IOException unreadable) {
                // An unwalkable tree is its own problem; the lint stays quiet.
            }
        }
    }

    private void lintWorkflow(Path appHome, AppConfig config, WorkflowFile workflow,
            List<LintFinding> findings) {
        String source = LintSupport.relative(appHome, workflow.source());
        UnknownKeyRules.lintUnknownKeys(context, appHome, workflow.source(),
                WorkflowDefinition.class, Set.of(),
                findings);
        WorkflowDefinition def = workflow.definition();
        String id = def.id();
        Path dir = workflow.source().getParent();

        Set<String> states = new LinkedHashSet<>();
        int initialMarked = 0;
        for (StateSpec state : def.states()) {
            if (state.id() != null) {
                states.add(state.id());
            }
            if (state.isInitial()) {
                initialMarked++;
            }
        }
        if (def.initial() != null && !states.contains(def.initial())) {
            findings.add(new LintFinding(UNDECLARED_STATE, ERROR, source, "workflow '" + id
                    + "' initial state '" + def.initial() + "' is not declared in states"));
        }
        if (initialMarked > 1) {
            findings.add(new LintFinding(INVALID_INITIAL_STATE, ERROR, source,
                    "workflow '" + id + "' declares more than one initial state"));
        }

        Set<String> transitionIds = new LinkedHashSet<>();
        Map<String, String> transitionFrom = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (TransitionSpec t : def.transitions()) {
            if (t.id() != null) {
                transitionIds.add(t.id());
                transitionFrom.put(t.id(), t.from());
            }
            String where = "workflow '" + id + "' transition '" + t.id() + "'";
            if (t.from() == null || !states.contains(t.from())) {
                findings.add(new LintFinding(UNDECLARED_STATE, ERROR, source,
                        where + " from-state '" + t.from() + "' is not declared in states"));
            } else {
                outgoing.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
            }
            if (t.to() == null || !states.contains(t.to())) {
                findings.add(new LintFinding(UNDECLARED_STATE, ERROR, source,
                        where + " to-state '" + t.to() + "' is not declared in states"));
            }
            lintGuard(t.guard(), dir, where, source, findings);
            lintStamp(t, where, source, findings);
            if (t.commandFile() != null && !Files.isRegularFile(dir.resolve(t.commandFile()))) {
                findings.add(new LintFinding(MISSING_TRANSITION_REFERENCE, ERROR, source,
                        where + " references missing command '" + t.commandFile() + "'"));
            }
            if (t.assign() != null && t.assign().file() != null
                    && !Files.isRegularFile(dir.resolve(t.assign().file()))) {
                findings.add(new LintFinding(MISSING_TRANSITION_REFERENCE, ERROR, source,
                        where + " references missing assignee file '" + t.assign().file() + "'"));
            }
        }

        if (def.initial() != null && states.contains(def.initial())) {
            Set<String> reachable = reachableStates(def.initial(), outgoing);
            for (StateSpec state : def.states()) {
                if (state.id() != null && !reachable.contains(state.id())) {
                    findings.add(new LintFinding(INVALID_INITIAL_STATE, ERROR, source,
                            "workflow '" + id + "' state '" + state.id()
                                    + "' is unreachable from the initial state"));
                }
            }
        }
        // One-action dispatches (docs/workflow-expressiveness.md slice 3): every member
        // exists and starts from one shared state (3112); a member without a guard that is
        // not last makes its followers unreachable (3113).
        for (io.tesseraql.yaml.model.DispatchSpec dispatch : def.dispatch()) {
            String where = "workflow '" + id + "' dispatch '" + dispatch.id() + "'";
            if (dispatch.id() != null && def.transitions().stream()
                    .anyMatch(t -> dispatch.id().equals(t.id()))) {
                findings.add(new LintFinding(INVALID_DISPATCH, ERROR, source,
                        where + " collides with a transition of the same id"));
            }
            if (dispatch.oneOf().size() < 2) {
                findings.add(new LintFinding(INVALID_DISPATCH, ERROR, source,
                        where + " needs at least two member transitions"));
            }
            String sharedFrom = null;
            io.tesseraql.yaml.model.SecuritySpec sharedSecurity = null;
            boolean securitySeen = false;
            for (int i = 0; i < dispatch.oneOf().size(); i++) {
                String member = dispatch.oneOf().get(i);
                io.tesseraql.yaml.model.TransitionSpec found = def.transitions().stream()
                        .filter(t -> member.equals(t.id())).findFirst().orElse(null);
                if (found == null) {
                    findings.add(new LintFinding(INVALID_DISPATCH, ERROR, source,
                            where + " names unknown transition '" + member + "'"));
                    continue;
                }
                if (sharedFrom == null) {
                    sharedFrom = found.from();
                } else if (!sharedFrom.equals(found.from())) {
                    findings.add(new LintFinding(INVALID_DISPATCH, ERROR, source,
                            where + " members start from different states ('" + sharedFrom
                                    + "' vs '" + found.from() + "')"));
                }
                // A dispatch is one action, one audience: every member must carry the
                // same effective security spec — the selector has none of its own, each
                // attempt enforces its member's.
                io.tesseraql.yaml.model.SecuritySpec effective = found.security() != null
                        ? found.security()
                        : def.security();
                if (!securitySeen) {
                    sharedSecurity = effective;
                    securitySeen = true;
                } else if (!java.util.Objects.equals(sharedSecurity, effective)) {
                    findings.add(new LintFinding(INVALID_DISPATCH, ERROR, source,
                            where + " members carry different security specs"));
                }
                if (found.guard() == null && i < dispatch.oneOf().size() - 1) {
                    findings.add(new LintFinding(UNREACHABLE_DISPATCH_MEMBER, WARNING, source,
                            where + " member '" + member + "' has no guard and is not last -"
                                    + " the members after it are unreachable"));
                }
                // One name, one evaluation (docs/transition-engine.md track B): a member
                // alias shadowing a dispatch-level alias could only confuse.
                for (String alias : dispatch.decide().keySet()) {
                    if (found.decide().containsKey(alias)) {
                        findings.add(new LintFinding(INVALID_DISPATCH, ERROR, source,
                                where + " decide alias '" + alias + "' collides with member '"
                                        + member + "' declaring its own '" + alias + "'"));
                    }
                }
            }
        }
        for (StateSpec state : def.states()) {
            boolean hasOutgoing = outgoing.containsKey(state.id());
            if (state.isTerminal() && hasOutgoing) {
                findings.add(new LintFinding(STATE_TRANSITION_DEAD_END, WARNING, source,
                        "workflow '" + id + "' terminal state '" + state.id()
                                + "' has an outgoing transition"));
            }
            if (!state.isTerminal() && !hasOutgoing) {
                findings.add(new LintFinding(STATE_TRANSITION_DEAD_END, WARNING, source,
                        "workflow '" + id + "' non-terminal state '" + state.id()
                                + "' has no outgoing transition (dead end)"));
            }
        }

        for (DeadlineSpec deadline : def.deadlines()) {
            String where = "workflow '" + id + "' deadline on '" + deadline.state() + "'";
            if (deadline.state() != null && !states.contains(deadline.state())) {
                findings.add(new LintFinding(UNDECLARED_STATE, ERROR, source,
                        where + " names a state not declared in states"));
            }
            DeadlineSpec.OnBreachSpec onBreach = deadline.onBreach();
            if (onBreach != null) {
                if (onBreach.escalate() != null && !onBreach.escalate().isBlank()) {
                    if (!transitionIds.contains(onBreach.escalate())) {
                        findings.add(new LintFinding(MISSING_TRANSITION_REFERENCE, ERROR, source,
                                where + " escalate '" + onBreach.escalate()
                                        + "' is not a declared transition"));
                    } else if (!java.util.Objects.equals(transitionFrom.get(onBreach.escalate()),
                            deadline.state())) {
                        // The sweeper auto-fires it from the deadline's state, so it could never
                        // advance from a different from-state.
                        findings.add(new LintFinding(ESCALATION_FROM_WRONG_STATE, ERROR, source,
                                where + " escalate '" + onBreach.escalate()
                                        + "' starts from '"
                                        + transitionFrom.get(onBreach.escalate())
                                        + "', not the deadline's state"));
                    }
                }
                if (onBreach.reassign() != null && onBreach.reassign().file() != null
                        && !Files.isRegularFile(dir.resolve(onBreach.reassign().file()))) {
                    findings.add(new LintFinding(MISSING_TRANSITION_REFERENCE, ERROR, source, where
                            + " references missing reassign file '"
                            + onBreach.reassign().file() + "'"));
                }
            }
        }

        lintWorkflowMode(def, config, source, findings);
    }

    /**
     * Lints a transition's decision stamps (docs/workflow-expressiveness.md slice 2): a
     * column must be a plain identifier — the only string reaching an UPDATE's column
     * position — and a {@code decision.*} value must name a declared {@code decide:} alias
     * ({@code TQL-WORKFLOW-3111}). A dotted string outside the whitelist roots is stamped
     * as a literal; the common context roots get a warning so a typo is not silent.
     */
    private void lintStamp(io.tesseraql.yaml.model.TransitionSpec transition, String where,
            String source, List<LintFinding> findings) {
        transition.stamp().forEach((column, value) -> {
            if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(column)) {
                findings.add(new LintFinding(INVALID_STAMP, ERROR, source,
                        where + " stamp column '" + column + "' is not a plain identifier"));
            }
            if (value instanceof String path) {
                if (path.startsWith("decision.")) {
                    String alias = path.split("\\.").length > 1 ? path.split("\\.")[1] : "";
                    if (!transition.decide().containsKey(alias)) {
                        findings.add(new LintFinding(INVALID_STAMP, ERROR, source,
                                where + " stamps '" + path + "' but declares no decide: entry '"
                                        + alias + "'"));
                    }
                } else if (path.matches("(task|params|body|query|path|audit)\\..+")) {
                    findings.add(new LintFinding(INVALID_STAMP, WARNING, source,
                            where + " stamp value '" + path + "' is outside the "
                                    + "decision/document/principal whitelist and will be "
                                    + "stamped as a literal string"));
                }
            }
        });
    }

    /**
     * Lints a guard in either form (docs/workflow-expressiveness.md): the expression form
     * parses and reads only allowed roots ({@code TQL-WORKFLOW-3103}); the SQL form names
     * exactly one of expression/file ({@code 3108}), the file exists ({@code 3104}), and the
     * file is a query — a guard must never write ({@code 3109}).
     */
    private void lintGuard(io.tesseraql.yaml.model.GuardSpec guard, Path dir, String where,
            String source, List<LintFinding> findings) {
        if (guard == null) {
            return;
        }
        boolean hasExpression = guard.expression() != null && !guard.expression().isBlank();
        boolean hasFile = guard.file() != null && !guard.file().isBlank();
        if (hasExpression == hasFile) {
            findings.add(new LintFinding(INVALID_GUARD_DECLARATION, ERROR, source,
                    where + " guard must declare exactly one of an expression or a file"));
            return;
        }
        if (hasFile) {
            Path file = dir.resolve(guard.file());
            if (!Files.isRegularFile(file)) {
                findings.add(new LintFinding(MISSING_TRANSITION_REFERENCE, ERROR, source,
                        where + " references missing guard file '" + guard.file() + "'"));
                return;
            }
            String sql;
            try {
                sql = Files.readString(file);
            } catch (java.io.IOException unreadable) {
                findings.add(new LintFinding(MISSING_TRANSITION_REFERENCE, ERROR, source,
                        where + " guard file '" + guard.file() + "' is unreadable: "
                                + unreadable.getMessage()));
                return;
            }
            String head = sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)^\\s*--.*$", " ").strip()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!head.startsWith("select") && !head.startsWith("with")) {
                findings.add(new LintFinding(GUARD_FILE_WRITES, ERROR, source,
                        where + " guard file '" + guard.file()
                                + "' must be a query - a guard never writes"));
            }
            return;
        }
        Expr expr;
        try {
            expr = ExpressionParser.parse(guard.expression());
        } catch (RuntimeException ex) {
            findings.add(new LintFinding(INVALID_GUARD_EXPRESSION, ERROR, source,
                    where + " guard is not a valid expression: " + ex.getMessage()));
            return;
        }
        List<List<String>> paths = new ArrayList<>();
        LintSupport.collectGuardPaths(expr, paths);
        for (List<String> path : paths) {
            if (!path.isEmpty() && !GUARD_ROOTS.contains(path.get(0))) {
                findings.add(new LintFinding(INVALID_GUARD_EXPRESSION, ERROR, source,
                        where + " guard references '" + String.join(".", path)
                                + "'; allowed roots are document, task, principal, decision"));
            }
        }
    }

    private static Set<String> reachableStates(String start, Map<String, List<String>> outgoing) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            String state = queue.poll();
            for (String next : outgoing.getOrDefault(state, List.of())) {
                if (next != null && reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    /** Checks the document fields the declared mode requires are present (roadmap Phase 28). */
    private void lintWorkflowMode(WorkflowDefinition def, AppConfig config, String source,
            List<LintFinding> findings) {
        String mode = def.mode();
        if (mode == null || mode.isBlank()) {
            mode = config.getString("tesseraql.workflow.mode").orElse("app");
        }
        boolean managed = "managed".equalsIgnoreCase(mode);
        WorkflowDefinition.DocumentSpec doc = def.document();
        if (doc == null) {
            findings.add(new LintFinding(INCOMPLETE_WORKFLOW_DOCUMENT, ERROR, source,
                    "workflow '" + def.id() + "' declares no document"));
            return;
        }
        List<String> missing = new ArrayList<>();
        if (LintSupport.isBlank(doc.table())) {
            missing.add("document.table");
        }
        if (LintSupport.isBlank(doc.key())) {
            missing.add("document.key");
        }
        if (managed) {
            if (LintSupport.isBlank(doc.type())) {
                missing.add("document.type");
            }
        } else if (LintSupport.isBlank(doc.stateColumn())) {
            missing.add("document.stateColumn");
        }
        if (!missing.isEmpty()) {
            findings.add(new LintFinding(INCOMPLETE_WORKFLOW_DOCUMENT, ERROR, source,
                    "workflow '" + def.id() + "' in " + (managed ? "managed" : "app")
                            + " mode requires " + String.join(", ", missing)));
        }
    }
}
