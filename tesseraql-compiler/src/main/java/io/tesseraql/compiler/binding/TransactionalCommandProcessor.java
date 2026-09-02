package io.tesseraql.compiler.binding;

import io.tesseraql.core.dialect.SqlErrorKind;
import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.outbox.OutboxStore;
import io.tesseraql.core.sequence.DocumentSequences;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.validation.ValidationRules;
import io.tesseraql.core.workflow.WorkflowStore;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.TenantRouting;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.ErrorsSpec;
import io.tesseraql.yaml.model.ValidationRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Executes a command's SQL steps and its outbox event in one transaction (design ch. 39.2,
 * roadmap Phase 18): one business operation, not one statement.
 *
 * <p>Steps run in their authored order on a single connection. Each step publishes its result
 * into the execution context under {@code steps.<name>} — affected rows, captured generated
 * keys ({@code steps.<name>.keys.<column>}), query rows, or an allocated document-sequence
 * value ({@code steps.<name>.value}) — so later steps and the response can bind it. On any
 * failure the transaction rolls back: every step, sequence allocation, and the event happen
 * together or not at all.
 *
 * <p>The canonical audit binds {@code /* audit.user *}{@code /} and {@code /* audit.now *}{@code /}
 * resolve from the authenticated principal and one clock reading per command, so audit columns
 * stay explicit in the SQL. Declared row-count expectations turn silent lost updates into
 * {@code 409 Conflict} with a usable hint, and declared constraint mappings turn unique /
 * foreign-key violations into field-level error payloads.
 *
 * <p>The route's {@code validate:} rules (roadmap Phase 19) evaluate first, inside the same
 * transaction: cross-field expression rules against the execution context and validation SQL
 * rules on the command's connection. Any violation rejects the request with a field-scoped
 * {@code 422 Unprocessable Entity} before a single step writes.
 *
 * <p>The route's {@code notify:} declarations (roadmap Phase 20) enqueue last, still inside the
 * transaction: each fired notification becomes a {@code NOTIFICATION} outbox event, so a rolled
 * back command never notifies and a committed one notifies at-least-once. Event ids publish into
 * the context as {@code notify.<id>.eventId}.
 *
 * <p>The route's {@code publish:} block (roadmap Phase 27) enqueues a domain event onto the same
 * transactional outbox as an {@code EVENT}, published to a messaging channel after the commit — so
 * a command emitting events to another system keeps the all-or-nothing guarantee. Its id publishes
 * into the context as {@code publish.eventId}.
 */
public final class TransactionalCommandProcessor implements Step {

    private static final TqlErrorCode TX_ERROR = new TqlErrorCode(TqlDomain.SQL, 2600);
    private static final TqlErrorCode NO_STORE = new TqlErrorCode(TqlDomain.SQL, 2601);
    /** TQL-SQL-2602: a row-count expectation failed with onMismatch: error. */
    private static final TqlErrorCode EXPECT_FAILED = new TqlErrorCode(TqlDomain.SQL, 2602);
    /** TQL-SQL-2611: a sequence step needs the runtime's DocumentSequences bean. */
    private static final TqlErrorCode NO_SEQUENCES = new TqlErrorCode(TqlDomain.SQL, 2611);
    // The same code the route-level SQL path raises, because it is the same failure.
    /** TQL-LD-0001: result materialization exceeded the configured maxRows. */
    private static final TqlErrorCode MATERIALIZATION_OVERFLOW = new TqlErrorCode(TqlDomain.LD, 1);
    /** TQL-ROUTE-3102: the route's steps declaration is invalid (fail fast at startup). */
    private static final TqlErrorCode INVALID_STEPS = new TqlErrorCode(TqlDomain.ROUTE, 3102);
    /** TQL-SQL-4092: a row-count expectation failed, reported as an optimistic-lock conflict. */
    private static final TqlErrorCode EXPECT_CONFLICT = new TqlErrorCode(TqlDomain.SQL, 4092);
    /** TQL-SQL-4094: a declared lock refused a stale write (docs/edit-conflict.md decision 5). */
    private static final TqlErrorCode LOCK_CONFLICT = new TqlErrorCode(TqlDomain.SQL, 4094);
    /** TQL-FIELD-4220: declarative validation rejected the input (HTTP 422). */
    private static final TqlErrorCode VALIDATION_FAILED = new TqlErrorCode(TqlDomain.FIELD, 4220);
    // Portable constraint-violation codes, mapped to HTTP statuses by ErrorResponseRenderer.
    private static final TqlErrorCode UNIQUE_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4090);
    private static final TqlErrorCode FK_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4091);
    private static final TqlErrorCode NOT_NULL_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4001);
    private static final TqlErrorCode CHECK_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4002);
    /** TQL-SQL-4093: a serialization failure or deadlock; the write may succeed if retried (HTTP 409). */
    private static final TqlErrorCode SERIALIZATION = new TqlErrorCode(TqlDomain.SQL, 4093);
    // The TQL-WORKFLOW-3201/3202/3203/3204 pipeline codes are raised by the
    // TransitionExecutor (docs/transition-engine.md), where the pipeline lives.
    /** TQL-WORKFLOW-3210: a managed transition needs the runtime's WorkflowStore bean. */
    private static final TqlErrorCode NO_WORKFLOW_STORE = new TqlErrorCode(TqlDomain.WORKFLOW,
            3210);

    /** The reserved bind namespace for the canonical audit binds. */
    private static final String AUDIT = "audit";

    private final String routeId;
    private final List<Step> steps;
    private final ValidationRules validation;
    private final io.tesseraql.core.decision.DecisionTables decisions;
    private final List<io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify> notifications;
    private final io.tesseraql.yaml.messaging.PublishEvents.CompiledPublish publish;
    private final ExecutionBounds defaultBounds;
    private static final System.Logger LOG = System
            .getLogger(TransactionalCommandProcessor.class.getName());
    private final String datasourceName;
    private final OutboxEvents outboxEvents;
    private final ErrorsSpec errors;
    private final String appName;
    private final String dialect;
    private final WorkflowBinding workflow;
    /** The route's declared lock column (docs/edit-conflict.md decision 1), or null. */
    private final String lock;
    /**
     * The compiled {@code lookup:} references of the command's input fields
     * (docs/reference-lookup.md decision 3), existence-checked on the command's own
     * connection beside the validation rules. Set by the compiler after construction;
     * empty for a command without lookup fields.
     */
    private List<LookupReferences.Compiled> lookups = List.of();

    /**
     * A compiled step: a parsed 2-way SQL statement or a managed sequence allocation. Its result
     * publishes under {@code steps.<name>}.
     */
    private record Step(String name, List<SqlNode> nodes, String sourcePath,
            String mode, Map<String, String> params, List<String> keys, Binding.Expect expect,
            String sequence, ExecutionBounds bounds, io.tesseraql.core.expr.Expr when,
            Map<String, Integer> outTypes, boolean locked) {

        boolean isSequence() {
            return sequence != null;
        }
    }

    /**
     * The JDBC types an {@code out:} declaration may name (docs/sql-execution-shapes.md
     * structural decision 7); an unknown keyword fails the build, not the first request.
     */
    private static final Map<String, Integer> OUT_TYPE_KEYWORDS = Map.of(
            "varchar", java.sql.Types.VARCHAR,
            "numeric", java.sql.Types.NUMERIC,
            "integer", java.sql.Types.INTEGER,
            "bigint", java.sql.Types.BIGINT,
            "boolean", java.sql.Types.BOOLEAN,
            "date", java.sql.Types.DATE,
            "timestamp", java.sql.Types.TIMESTAMP,
            "double", java.sql.Types.DOUBLE);

    /**
     * Builds the processor for a command route.
     *
     * <p>{@code workflow} is non-null only for a synthesized workflow transition route (roadmap
     * Phase 28), where it makes the processor advance the document's state, check the
     * transition's guard, and append history inside the command's transaction.
     *
     * @param routeId        the route the processor serves — a document's id, or the id
     *                       synthesized for a transition
     * @param declared       what the command declares (its statements and the blocks that ride
     *                       their transaction)
     * @param stepFile       resolves a step's or rule's SQL file reference to its
     *                       (dialect-resolved) path
     * @param datasourceName the connector the transaction runs on
     * @param dialect        that connector's dialect id
     * @param appName        the app, for the outbox and notification envelopes
     * @param workflow       the transition binding, or null for a plain command
     * @param defaultBounds  the execution bounds a step inherits when it declares none
     */
    public TransactionalCommandProcessor(String routeId, CommandDeclaration declared,
            java.util.function.Function<String, Path> stepFile, String datasourceName,
            String dialect, String appName, WorkflowBinding workflow,
            ExecutionBounds defaultBounds) {
        this(routeId, declared, stepFile, datasourceName, dialect, appName, workflow,
                defaultBounds, ExpressionFunctions.processDefault());
    }

    /**
     * As {@link #TransactionalCommandProcessor(String, CommandDeclaration,
     * java.util.function.Function, String, String, String, WorkflowBinding, ExecutionBounds)},
     * resolving custom calls against {@code functions}.
     */
    public TransactionalCommandProcessor(String routeId, CommandDeclaration declared,
            java.util.function.Function<String, Path> stepFile, String datasourceName,
            String dialect, String appName, WorkflowBinding workflow,
            ExecutionBounds defaultBounds, ExpressionFunctions functions) {
        this.defaultBounds = defaultBounds;
        this.workflow = workflow;
        this.routeId = routeId;
        this.datasourceName = datasourceName;
        this.outboxEvents = declared.outbox() == null
                ? null
                : new OutboxEvents(declared.outbox(), appName);
        this.notifications = io.tesseraql.yaml.notify.NotifyEvents.compileAll(routeId,
                declared.notifications(), functions);
        this.publish = declared.publish() == null
                ? null
                : io.tesseraql.yaml.messaging.PublishEvents.compile(routeId, declared.publish());
        this.appName = appName;
        this.errors = declared.errors() == null ? new ErrorsSpec(null) : declared.errors();
        this.dialect = dialect;
        // A plain command needs a statement; a workflow transition may be state-only (the framework
        // advances the state and appends history with no author command of its own).
        if (declared.steps().isEmpty() && workflow == null) {
            throw invalid("a command route needs a steps: declaration");
        }
        this.lock = declared.lock();
        this.steps = compile(declared.steps(), stepFile, functions);
        this.validation = compileValidation(declared.validate(), stepFile, functions);
        // The one decide: compile (docs/decision-tables.md), shared with the transition
        // executor — a workflow transition's decisions ride its CompiledTransition instead
        // and evaluate inside the executor's pipeline.
        this.decisions = io.tesseraql.yaml.decision.DecisionSets.compileUses(declared.decide(),
                dialect, functions);
    }

    /** The lookup existence checks this command runs (docs/reference-lookup.md decision 3). */
    public TransactionalCommandProcessor lookups(List<LookupReferences.Compiled> compiled) {
        this.lookups = List.copyOf(compiled);
        return this;
    }

    /** Compiles the validate: block, failing fast on misdeclared rules (roadmap Phase 19). */
    private ValidationRules compileValidation(Map<String, ValidationRule> validate,
            java.util.function.Function<String, Path> ruleFile,
            ExpressionFunctions functions) {
        List<ValidationRules.Rule> compiled = new ArrayList<>();
        (validate == null ? Map.<String, ValidationRule>of() : validate)
                .forEach((id, rule) -> {
                    if (rule.isExpression() == rule.isSql()) {
                        throw invalid("validation rule '" + id
                                + "' must declare exactly one of rule: or file:");
                    }
                    if (rule.isExpression()) {
                        if (!rule.params().isEmpty()) {
                            throw invalid("validation rule '" + id
                                    + "': params apply to SQL rules only");
                        }
                        compiled.add(ValidationRules.expression(id, rule.when(), rule.rule(),
                                rule.field(), rule.code(), rule.message(), functions));
                    } else {
                        Path file = ruleFile.apply(rule.file());
                        compiled.add(ValidationRules.sql(id, rule.when(), read(file),
                                file.toString(), rule.params(), rule.field(), rule.code(),
                                rule.message(), functions));
                    }
                });
        return new ValidationRules(compiled);
    }

    private List<Step> compile(Map<String, Binding> declaredSteps,
            java.util.function.Function<String, Path> stepFile,
            ExpressionFunctions functions) {
        Map<String, Binding> bindings = declaredSteps;
        List<Step> compiled = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String lockCarrier = null;
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            String name = entry.getKey();
            Binding binding = entry.getValue();
            validate(name, binding, seen);
            io.tesseraql.core.expr.Expr when = binding.when() == null || binding.when().isBlank()
                    ? null
                    : io.tesseraql.core.expr.ExpressionParser.parse(binding.when(), functions);
            if (binding.isSequence()) {
                compiled.add(new Step(name, null, null, "sequence",
                        binding.params(), List.of(), null, binding.sequence(),
                        boundsFor(binding), when, Map.of(), false));
            } else {
                Path file = stepFile.apply(binding.file());
                // Steps default to update: a command writes.
                String mode = binding.mode() == null || binding.mode().isBlank()
                        ? "update"
                        : binding.mode();
                // Expectations and key capture count affected rows, which query mode never has
                // — and a call answers no count at all.
                if (!"update".equals(mode)
                        && (binding.expect() != null || !binding.keys().isEmpty())) {
                    throw invalid("step '" + name + "': expect/keys need an update statement -"
                            + " declare mode: update");
                }
                List<SqlNode> nodes = Sql2WayParser.parse(read(file), functions);
                int[] lockCounts = lockCounts(nodes);
                boolean locked = lockCounts[0] > 0;
                validateLock(name, mode, binding, lockCounts, lockCarrier);
                if (locked) {
                    lockCarrier = name;
                }
                // A declared lock implies its expectation (docs/edit-conflict.md decision 1):
                // exactly one affected row, refused as a conflict. It attaches to the carrier
                // step alone; every other step of a multi-step command is untouched.
                Binding.Expect expect = locked
                        ? new Binding.Expect(1, "conflict")
                        : binding.expect();
                compiled.add(new Step(name, nodes,
                        file.toString(), mode,
                        binding.params(), binding.keys(), expect, null,
                        boundsFor(binding), when, outTypes(name, mode, binding), locked));
            }
            seen.add(name);
        }
        return List.copyOf(compiled);
    }

    /**
     * The bounds for one step: its own {@code timeoutSeconds:}/{@code materialize:} when
     * declared, otherwise the app-wide defaults the compiler resolved — the same precedence the
     * route-level SQL path applies.
     */
    private ExecutionBounds boundsFor(Binding binding) {
        if (defaultBounds == null) {
            return new ExecutionBounds(0, -1, "fail");
        }
        int timeout = binding.timeoutSeconds() != null
                ? Math.max(0, binding.timeoutSeconds())
                : defaultBounds.timeoutSeconds();
        int maxRows = binding.materialize() != null && binding.materialize().maxRows() != null
                ? binding.materialize().maxRows()
                : defaultBounds.maxRows();
        String onOverflow = binding.materialize() != null
                && binding.materialize().onOverflow() != null
                        ? binding.materialize().onOverflow()
                        : defaultBounds.onOverflow();
        return new ExecutionBounds(timeout, maxRows, onOverflow);
    }

    /**
     * The declared OUT parameters of a {@code mode: call} step, compiled to their JDBC types
     * (docs/sql-execution-shapes.md structural decision 7). Refusals happen here, at build:
     * {@code out:} on any other mode, and an unknown type keyword.
     */
    private Map<String, Integer> outTypes(String name, String mode, Binding binding) {
        if (binding.out().isEmpty()) {
            return Map.of();
        }
        if (!"call".equals(mode)) {
            throw invalid("step '" + name + "': out: applies to mode: call only");
        }
        Map<String, Integer> types = new LinkedHashMap<>();
        binding.out().forEach((outName, keyword) -> {
            Integer type = OUT_TYPE_KEYWORDS.get(keyword);
            if (type == null) {
                throw invalid("step '" + name + "': out." + outName + " declares unknown type '"
                        + keyword + "' - one of "
                        + new java.util.TreeSet<>(OUT_TYPE_KEYWORDS.keySet()));
            }
            types.put(outName, type);
        });
        return java.util.Collections.unmodifiableMap(types);
    }

    /**
     * How many lock directives this statement carries, in total and at the top level.
     *
     * <p>The two counts differ only for a directive nested inside a {@code /*%if*}{@code /} or a
     * {@code /*%for*}{@code /}, and that difference is a refusal (see {@link #validateLock}): a
     * lock that renders away is a lock that is not there, and the write would then satisfy its
     * own implied row-count expectation on the author's remaining predicates alone.
     */
    private static int[] lockCounts(List<SqlNode> nodes) {
        int[] counts = {0, 0};
        SqlNode.walk(nodes, node -> {
            if (node instanceof SqlNode.Lock) {
                counts[0]++;
            }
        });
        counts[1] = (int) nodes.stream().filter(SqlNode.Lock.class::isInstance).count();
        return counts;
    }

    /**
     * The two step-shaped lock refusals (docs/edit-conflict.md decision 1). Both read the raw
     * declaration rather than the compiled step, because the compiled one already carries the
     * expectation the lock implies — reading that back would refuse every locked route.
     *
     * <p>The route-shaped half of the pairing lives in the compiler, which is the only place that
     * holds the route. Here we can only refuse the direction that is visible from a statement: a
     * directive on a route that declared no lock at all.
     */
    private void validateLock(String name, String mode, Binding binding, int[] counts,
            String earlierCarrier) {
        if (counts[0] == 0) {
            return;
        }
        if (!"update".equals(mode)) {
            // The implied expectation counts affected rows, which a query never has and a call
            // never answers - so a lock here would refuse every request with a conflict it
            // cannot explain.
            throw invalid("step '" + name + "': a lock directive needs an update statement -"
                    + " declare mode: update");
        }
        if (counts[0] != counts[1]) {
            throw invalid("step '" + name + "': a lock directive inside an /*%if*/ or a /*%for*/"
                    + " would render away on the branch that omits it, and the write"
                    + " would then meet its own row-count expectation unlocked — the lock predicate"
                    + " is not conditional (docs/edit-conflict.md)");
        }
        if (counts[0] > 1) {
            throw invalid("step '" + name + "': " + counts[0] + " lock directives - a route has"
                    + " exactly one lock");
        }
        if (lock == null || lock.isBlank()) {
            throw invalid("step '" + name + "': a lock directive needs a route-level lock:"
                    + " declaration naming the column to compare (docs/edit-conflict.md)");
        }
        if (binding.expect() != null) {
            throw invalid("step '" + name + "': expect: cannot be declared beside a lock"
                    + " directive - lock: implies expect: { rowCount: 1, onMismatch: conflict },"
                    + " and two statements of one intent can disagree");
        }
        if (earlierCarrier != null) {
            throw invalid("step '" + name + "': a second lock directive - step '" + earlierCarrier
                    + "' already carries the route's one lock");
        }
    }

    /** Fail-fast validation of one step declaration (runs at route build time). */
    private void validate(String name, Binding binding, java.util.Set<String> earlier) {
        if (binding.isContract() || binding.isService()) {
            throw invalid("step '" + name + "': contract/service bindings are not supported in"
                    + " command steps - use a SQL file or a sequence");
        }
        if (binding.isSequence() == (binding.file() != null)) {
            throw invalid("step '" + name + "' must declare exactly one of file: or sequence:");
        }
        if (binding.isSequence() && (!binding.keys().isEmpty() || binding.expect() != null)) {
            throw invalid("step '" + name + "': keys/expect do not apply to a sequence step");
        }
        if (binding.expect() != null && binding.expect().rowCount() == null) {
            throw invalid("step '" + name + "': expect.rows is required");
        }
        if (binding.expect() != null
                && !List.of("conflict", "error").contains(binding.expect().effectiveOnMismatch())) {
            throw invalid("step '" + name + "': expect.onMismatch must be conflict or error");
        }
        if (binding.params().containsKey(AUDIT)) {
            throw invalid("step '" + name + "': the bind name 'audit' is reserved for the"
                    + " canonical audit binds");
        }
        if (binding.params().containsKey(io.tesseraql.core.sql.LockBinding.PARAM)) {
            throw invalid("step '" + name + "': the bind name '"
                    + io.tesseraql.core.sql.LockBinding.PARAM + "' is reserved for the route's"
                    + " declared lock (docs/edit-conflict.md)");
        }
        if (binding.params().containsKey("out")) {
            throw invalid("step '" + name + "': the bind name 'out' is reserved for a call"
                    + " statement's OUT parameters (docs/sql-execution-shapes.md)");
        }
        for (Map.Entry<String, String> param : binding.params().entrySet()) {
            String expr = param.getValue();
            if (expr != null && expr.startsWith("steps.")) {
                String referenced = expr.split("\\.")[1];
                if (!earlier.contains(referenced)) {
                    throw invalid("step '" + name + "': param '" + param.getKey()
                            + "' references step '" + referenced
                            + "' which is not an earlier step");
                }
            }
        }
    }

    /**
     * The data-scope resolver bound by the runtime, or the reject-any default so a
     * {@code /*%scope … *&#47;} in an app without scopes fails loudly instead of silently
     * writing unscoped rows.
     */
    private static io.tesseraql.core.sql.ScopeResolver scopeResolver(Exchange exchange) {
        io.tesseraql.core.sql.ScopeResolver resolver = exchange.beans().lookup(
                TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                io.tesseraql.core.sql.ScopeResolver.class);
        return resolver != null ? resolver : io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
    }

    private TqlException invalid(String message) {
        return new TqlException(INVALID_STEPS, "Route '" + routeId + "': " + message);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context == null) {
            context = new java.util.HashMap<>();
            exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        }
        // One clock reading per command, so every audit.now bind in the transaction agrees.
        Map<String, Object> audit = auditBinds(context);
        context.put(AUDIT, audit);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        context.put("steps", stepResults);

        // Tenant routing is resolved the same way the read path resolves it: a per-tenant
        // deployment must not commit a write to the shared pool its reads never touch.
        DataSource dataSource = TenantRouting.dataSource(exchange, datasourceName);
        boolean needsStore = outboxEvents != null || !notifications.isEmpty() || publish != null;
        OutboxStore store = !needsStore
                ? null
                : exchange.beans().lookup(
                        TesseraqlProperties.OUTBOX_STORE_BEAN, OutboxStore.class);
        if (needsStore && store == null) {
            throw new TqlException(NO_STORE, "Outbox store is not configured");
        }

        // The statement layer, per exchange (docs/contract-sql-execution.md structural
        // decision 1): every statement in this transaction — steps, validation rules, decision
        // lookups, the workflow pipeline — runs bounded, classified and spanned through the one
        // primitive. Cheap immutable; the tracer is looked up per request.
        io.tesseraql.core.sql.SqlStatement statements = io.tesseraql.core.sql.SqlStatement
                .onCallerConnections()
                .dialect(dialect)
                .timeoutSeconds(defaultBounds == null ? 0 : defaultBounds.timeoutSeconds())
                .surface("command")
                .tracer(tracer(exchange))
                .spanParent(exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT,
                        io.tesseraql.core.telemetry.SpanContext.class));

        // The same open-run-commit-restore bracket as SqlStatement.transact, kept by hand on
        // purpose: the command pipeline maps every failure through asTqlException for the
        // response contract, and threads the connection through workflow sessions, decisions,
        // and outbox inserts — riding transact would put a second exception vocabulary
        // (SqlStatementException) between those and the renderer for no behavioral gain. The
        // discipline is identical: suppressed rollback, log-don't-throw autocommit restore.
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Decisions evaluate first (docs/decision-tables.md): once per operation,
                // from the request context alone. A workflow transition's decisions evaluate
                // inside beginWorkflow instead — after the document binds (so wiring reads
                // document.*), still before the guard consumes decision.*.
                if (workflow == null && !decisions.isEmpty()) {
                    context.put(io.tesseraql.core.sql.AmbientBinds.DECISION,
                            decisions.evaluate(context, connection, statements));
                }
                // A workflow transition (roadmap Phase 28) checks legality and the guard inside the
                // transaction, before validation — the pipeline itself is the executor's
                // (docs/transition-engine.md); the route supplies the stores, the scope
                // resolver, and the caller's identity.
                io.tesseraql.yaml.workflow.TransitionExecutor.Session wf = workflow == null
                        ? null
                        : beginWorkflow(exchange, connection, context, statements);
                // A declared reference validates itself (docs/reference-lookup.md decision
                // 3): each lookup: field's bound id must match exactly one source row —
                // one indexed read on the command's connection, the currency a validation
                // SQL rule spends. Checked before the authored rules, like the binder's
                // own constraints.
                List<Map<String, Object>> unresolved = LookupReferences.violations(lookups,
                        statements, connection, scopeResolver(exchange), context);
                if (!unresolved.isEmpty()) {
                    throw TqlException.builder(VALIDATION_FAILED)
                            .message("Route '" + routeId + "': " + unresolved.size()
                                    + " lookup reference(s) did not resolve")
                            .details(Map.of("fields", unresolved))
                            .build();
                }
                // Validation runs first, inside the transaction (roadmap Phase 19): expression
                // rules against the bound context, SQL rules on the command's connection. A
                // violation rejects the request before a single step writes.
                List<Map<String, Object>> violations = validation.evaluate(context, connection,
                        scopeResolver(exchange), statements, null);
                if (!violations.isEmpty()) {
                    throw TqlException.builder(VALIDATION_FAILED)
                            .message("Route '" + routeId + "': validation rejected the input with "
                                    + violations.size() + " violation(s)")
                            .details(Map.of("fields", violations))
                            .build();
                }
                // Advance the state before the command (the conditional UPDATE turning a
                // concurrent transition into a 409) and apply the decision stamps — both the
                // executor's steps.
                if (wf != null) {
                    wf.advance(connection, context);
                }
                for (Step step : steps) {
                    // The ValidationRules.when contract, applied to steps: a falsy guard
                    // skips the step, and the skip is recorded — a later step or the
                    // response reading steps.<name>.* sees a declared absence, not a
                    // silently missing entry (docs/decision-tables.md "Acting on the
                    // result").
                    if (step.when() != null && !step.when()
                            .evalBoolean(new io.tesseraql.core.expr.EvaluationContext(context))) {
                        stepResults.put(step.name(), Map.of("skipped", true));
                        continue;
                    }
                    Map<String, Object> result = step.isSequence()
                            ? allocateSequence(exchange, connection, step)
                            : executeSql(exchange, connection, statements, step, context, audit);
                    stepResults.put(step.name(), result);
                }
                // The documented row-authority contract (docs/approval-workflow.md "guards and
                // scopes"), enforced by the executor before history/tasks commit.
                if (wf != null && !stepResults.isEmpty()) {
                    boolean anyExecuted = false;
                    int totalAffected = 0;
                    for (Object stepResult : stepResults.values()) {
                        if (!(stepResult instanceof Map<?, ?> result)) {
                            continue;
                        }
                        Object affected = result.get("affectedRows");
                        if (affected instanceof Integer rows) {
                            anyExecuted = true;
                            totalAffected += rows;
                        }
                    }
                    wf.enforceCommandRows(anyExecuted, totalAffected);
                }
                // Append the immutable history row, complete the prior tasks, and open the new
                // state's tasks — all in the same transaction (roadmap Phase 28), so the audit
                // record and the inbox change commit or roll back with the state change.
                if (wf != null) {
                    wf.store().appendHistory(connection, new WorkflowStore.History(null,
                            workflow.transition().docType(), wf.docId(),
                            workflow.transition().transitionId(),
                            wf.fromState(), workflow.transition().to(),
                            (String) audit.get("user"),
                            ((java.sql.Timestamp) audit.get("now")).toInstant(),
                            transitionComment(context)));
                    applyTasks(exchange, connection, statements, wf, context,
                            (String) audit.get("user"));
                }
                if (outboxEvents != null) {
                    String eventId = store.insert(connection, outboxEvents.build(context));
                    context.put("outbox", Map.of("eventId", eventId));
                }
                if (!notifications.isEmpty()) {
                    // Notifications enqueue in the same transaction (roadmap Phase 20): a rolled
                    // back command never notifies; a committed one notifies at-least-once. One
                    // naming its recipient honors that subject's per-channel opt-out (roadmap
                    // Phase 48) — decided at enqueue, so no outbox row exists: nothing to
                    // retry, nothing half-delivered.
                    io.tesseraql.core.account.PreferenceStore preferences = exchange.beans().lookup(
                            TesseraqlProperties.PREFERENCE_STORE_BEAN,
                            io.tesseraql.core.account.PreferenceStore.class);
                    String tenantId = context.get("principal") instanceof Principal p
                            ? p.tenantId()
                            : null;
                    Map<String, Object> enqueued = new LinkedHashMap<>();
                    // One clock for the whole block, so two entries declaring the same delay:
                    // come due together rather than a few microseconds apart.
                    java.time.Instant now = java.time.Instant.now();
                    for (var notification : notifications) {
                        if (!notification.fires(context)) {
                            continue;
                        }
                        if (notification.withdraws()) {
                            // The withdrawal rides this command's transaction, where the
                            // authority to cancel has already been established by the command
                            // itself (docs/notifications.md, "Scheduled delivery"). A rolled
                            // back cancellation withdraws nothing.
                            String key = notification.resolveCancel(context);
                            int withdrawn = key == null
                                    ? 0
                                    : store.withdraw(connection, appName, key);
                            enqueued.put(notification.id(), Map.of("withdrawn", withdrawn));
                            continue;
                        }
                        if (io.tesseraql.yaml.notify.NotifyOptOut.optedOut(notification,
                                context, preferences, tenantId)) {
                            enqueued.put(notification.id(), Map.of("optedOut", true));
                            continue;
                        }
                        // The resolved recipient rides the envelope (roadmap Phase 49) so
                        // addressed channel types (inbox) know who to deliver to.
                        String eventId = store.insert(connection,
                                notification.build(context, appName,
                                        notification.resolveRecipient(context), tenantId, now));
                        enqueued.put(notification.id(), Map.of("eventId", eventId));
                    }
                    context.put("notify", enqueued);
                }
                if (publish != null) {
                    // The published event enqueues in the same transaction (roadmap Phase 27): a
                    // rolled back command never publishes; a committed one publishes at-least-once.
                    String eventId = store.insert(connection, publish.build(context, appName));
                    context.put("publish", Map.of("eventId", eventId));
                }
                connection.commit();
            } catch (RuntimeException | SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    // A rollback that also fails must not replace the failure that matters.
                    ex.addSuppressed(rollback);
                }
                throw asTqlException(ex);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException restore) {
                    // The command's outcome is already decided; failing the exchange over the
                    // reset would report an outcome that did not happen — a committed command
                    // re-reported as a failure invites the retry that duplicates it.
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not restore autocommit after a command transaction: {0}",
                            restore.getMessage());
                }
            }
        }
        exchange.setBody(Map.copyOf(stepResults));
    }

    /** The canonical audit binds: the caller's identity and one clock reading (roadmap Phase 18). */
    private static Map<String, Object> auditBinds(Map<String, Object> context) {
        Object principal = context.get("principal");
        String user = null;
        if (principal instanceof Principal p) {
            user = p.loginId() != null ? p.loginId() : p.subject();
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("user", user);
        audit.put("now", java.sql.Timestamp.from(Instant.now()));
        return audit;
    }

    /**
     * Opens the transition through the {@code TransitionExecutor}
     * (docs/transition-engine.md): the route resolves the stores, the document key, the
     * scope resolver, and the caller's identity; the executor owns the pipeline itself.
     */
    private io.tesseraql.yaml.workflow.TransitionExecutor.Session beginWorkflow(
            Exchange exchange, Connection connection, Map<String, Object> context,
            io.tesseraql.core.sql.SqlStatement statements) throws SQLException {
        WorkflowStore store = workflow.transition().managed()
                ? lookupWorkflowStore(exchange)
                : workflow.appStore();
        EvaluationContext evaluation = new EvaluationContext(context);
        Object keyValue = evaluation.resolve(Arrays.asList(workflow.keyExpr().split("\\.")));
        String docId = keyValue == null ? null : String.valueOf(keyValue);
        Principal principal = context.get("principal") instanceof Principal p ? p : null;
        return io.tesseraql.yaml.workflow.TransitionExecutor.begin(connection,
                workflow.transition(),
                new io.tesseraql.yaml.workflow.TransitionExecutor.Collaborators(store,
                        lookupTaskStore(exchange), scopeResolver(exchange),
                        principal == null ? null : principal.subject(),
                        principal == null ? List.of() : principal.groups(),
                        statements, null),
                docId, context);
    }

    private WorkflowStore lookupWorkflowStore(Exchange exchange) {
        WorkflowStore store = exchange.beans().lookup(TesseraqlProperties.WORKFLOW_STORE_BEAN,
                WorkflowStore.class);
        if (store == null) {
            throw new TqlException(NO_WORKFLOW_STORE,
                    "Workflow '" + workflow.transition().workflowId()
                            + "' is managed but no workflow store is configured");
        }
        return store;
    }

    /** The task inbox store, bound when any workflow assigns tasks, or {@code null} otherwise. */
    private static WorkflowTaskStore lookupTaskStore(Exchange exchange) {
        return exchange.beans().lookup(
                TesseraqlProperties.WORKFLOW_TASK_STORE_BEAN, WorkflowTaskStore.class);
    }

    /**
     * The acting user's comment for the history row's {@code note}
     * (docs/workflow-surface.md decision 5): the transition's one declared body field,
     * already bound and length-checked by the request binder; blank records as null — an
     * empty note is no note.
     */
    private static String transitionComment(Map<String, Object> context) {
        Object body = context.get("body");
        if (!(body instanceof Map<?, ?> fields)) {
            return null;
        }
        Object comment = fields.get("comment");
        if (comment == null || String.valueOf(comment).isBlank()) {
            return null;
        }
        return String.valueOf(comment);
    }

    /**
     * Completes the document's open tasks (the ones the caller acted on) and opens the resulting
     * state's tasks from the transition's {@code assign} contract (roadmap Phase 28 slice 2). Runs
     * inside the transaction, after the command, so the inbox change commits with the transition.
     */
    private void applyTasks(Exchange exchange, Connection connection,
            io.tesseraql.core.sql.SqlStatement statements,
            io.tesseraql.yaml.workflow.TransitionExecutor.Session wf,
            Map<String, Object> context, String actor) throws SQLException {
        if (wf.taskStore() == null) {
            return;
        }
        wf.taskStore().completeOpenTasks(connection, workflow.transition().docType(),
                wf.docId(), actor);
        if (workflow.assignNodes() == null) {
            return;
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        workflow.assignParams().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        // Assign SQL binds what every other statement in this transaction binds. Without the
        // ambient seed a /* principal.loginId *&#47; here resolved to null rather than failing,
        // because a missing segment evaluates to null - a silent wrong answer.
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        // The command's own audit map, not a fresh one: re-reading the clock here would break
        // the guarantee that every audit.now in the transaction agrees.
        params.put(AUDIT, context.get(AUDIT));
        BoundSql bound = SqlRenderer.render(workflow.assignNodes(), params,
                scopeResolver(exchange), context);
        List<Map<String, Object>> rows = statements.read(connection, "workflow.assign", bound,
                io.tesseraql.core.sql.SqlStatement.cappedRows(dialect,
                        defaultBounds == null ? -1 : defaultBounds.maxRows(),
                        overflow("Workflow assign", null, defaultBounds)));
        // The opened task's deadline (roadmap Phase 28 slice 3): the to state's `within`, if any.
        Instant dueAt = workflow.dueWithinMillis() == null
                ? null
                : Instant.now().plusMillis(workflow.dueWithinMillis());
        for (Map<String, Object> row : rows) {
            String assignee = row.get("assignee") == null
                    ? null
                    : String.valueOf(row.get("assignee"));
            Object group = row.get("candidate_group");
            String candidateGroup = group == null ? null : String.valueOf(group);
            if (assignee != null || candidateGroup != null) {
                // Absence resolution (roadmap Phase 52): one hop at assignment time; the task
                // records who it was meant for, and the reminder targets who must act.
                io.tesseraql.core.workflow.Delegations.Resolved resolved = io.tesseraql.core.workflow.Delegations
                        .resolve(exchange.beans().lookup(
                                TesseraqlProperties.DELEGATION_STORE_BEAN,
                                io.tesseraql.core.workflow.DelegationStore.class),
                                wf.tenant(), assignee);
                wf.taskStore().openTask(connection, new WorkflowTaskStore.Task(
                        workflow.transition().docType(),
                        wf.docId(), workflow.transition().to(), resolved.assignee(),
                        candidateGroup, dueAt,
                        wf.tenant(), resolved.delegatedFrom()));
                enqueueAssignReminder(exchange, connection, context, resolved.assignee(),
                        candidateGroup);
            }
        }
    }

    /**
     * Enqueues the task-assignment reminder on the transaction's outbox (roadmap Phase 28 slice 3,
     * Phase 20 channels), so a rolled-back transition never notifies and a committed one notifies
     * at-least-once. The resolved {@code assignee}/{@code candidateGroup} are in the payload scope.
     */
    private void enqueueAssignReminder(Exchange exchange, Connection connection,
            Map<String, Object> context, String assignee, String candidateGroup) {
        if (workflow.assignNotify() == null) {
            return;
        }
        Map<String, Object> reminderContext = new LinkedHashMap<>(context);
        reminderContext.put("assignee", assignee);
        reminderContext.put("candidateGroup", candidateGroup);
        if (!workflow.assignNotify().fires(reminderContext)) {
            return;
        }
        OutboxStore store = exchange.beans().lookup(
                TesseraqlProperties.OUTBOX_STORE_BEAN, OutboxStore.class);
        if (store != null) {
            store.insert(connection, workflow.assignNotify().build(reminderContext, appName));
        }
    }

    private Map<String, Object> allocateSequence(Exchange exchange, Connection connection,
            Step step) {
        DocumentSequences sequences = exchange.beans().lookup(
                TesseraqlProperties.DOCUMENT_SEQUENCES_BEAN,
                DocumentSequences.class);
        if (sequences == null) {
            throw new TqlException(NO_SEQUENCES, "Route '" + routeId
                    + "': document sequences are not configured in this runtime");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", sequences.next(connection, step.sequence()));
        return result;
    }

    private Map<String, Object> executeSql(Exchange exchange, Connection connection,
            io.tesseraql.core.sql.SqlStatement statements, Step step,
            Map<String, Object> context, Map<String, Object> audit) throws SQLException {
        // Params resolve against the live context, so a later step binds earlier results
        // (steps.header.keys.id) the same way it binds request fields.
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        step.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        // Ambient principal.* binds (docs/ambient-params.md); declared params win by name,
        // and the audit namespace stays reserved.
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        params.put(AUDIT, audit);
        // The declared lock (docs/edit-conflict.md decision 2), seeded only on the step whose
        // statement carries the directive. Every other render path seeds nothing, so a stray
        // directive elsewhere refuses at TQL-SQL-2115 rather than rendering an unlocked write.
        if (step.locked()) {
            params.put(io.tesseraql.core.sql.LockBinding.PARAM,
                    exchange.getProperty(LockBinder.LOCK_PROPERTY));
        }
        // Row scoping applies to writes, not only reads: a /*%scope … *&#47; in an UPDATE's
        // WHERE is how an approval transition carries its row authority (docs/data-scoping.md).
        BoundSql bound = SqlRenderer.render(step.nodes(), params, scopeResolver(exchange),
                context);

        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        // The statement itself — prepare, bind, bound, execute, read, classify, span — is the
        // primitive's; this method keeps what is the command's: params, scoping, expectations,
        // the per-step bound override, and the steps.<name> result shape.
        io.tesseraql.core.sql.SqlStatement stepStatements = statements
                .timeoutSeconds(step.bounds().timeoutSeconds());
        String sqlId = step.sourcePath() == null ? step.name() : step.sourcePath();
        Map<String, Object> result = new LinkedHashMap<>();
        if ("call".equals(step.mode())) {
            // A stored call (docs/sql-execution-shapes.md structural decision 7): the OUT
            // values publish as steps.<name>.out.<name>; a call answers no affected count.
            result.put("out", stepStatements.call(connection, sqlId, bound, step.outTypes()));
        } else if ("query".equals(step.mode())) {
            List<Map<String, Object>> rows = stepStatements.read(connection, sqlId, bound,
                    io.tesseraql.core.sql.SqlStatement.cappedRows(dialect,
                            step.bounds().maxRows(),
                            overflow("Step '" + step.name() + "'", step.sourcePath(),
                                    step.bounds())));
            result.put("rows", rows);
            result.put("rowCount", rows.size());
        } else {
            io.tesseraql.core.sql.SqlStatement.WriteResult written = stepStatements
                    .update(connection, sqlId, bound, step.keys());
            result.put("affectedRows", written.affectedRows());
            if (!step.keys().isEmpty()) {
                result.put("keys", written.keys());
            }
        }
        recordExecution(exchange, step, result, startNanos, startedAt);

        if (step.expect() != null) {
            checkExpectation(step, (Integer) result.get("affectedRows"));
        }
        return result;
    }

    /**
     * The caller's half of a capped read: {@code onOverflow: warn} truncates with a log,
     * anything else refuses with the same code the route-level SQL path raises.
     */
    private static io.tesseraql.core.sql.SqlStatement.RowOverflow overflow(String label,
            String sourcePath, ExecutionBounds bounds) {
        return () -> {
            if (bounds != null && "warn".equals(bounds.onOverflow())) {
                LOG.log(System.Logger.Level.WARNING, "{0} result truncated at maxRows={1}",
                        label, bounds.maxRows());
                return;
            }
            throw TqlException.builder(MATERIALIZATION_OVERFLOW)
                    .message(label + " result exceeds maxRows="
                            + (bounds == null ? -1 : bounds.maxRows())
                            + " (narrow the statement, or raise"
                            + " materialize.maxRows)")
                    .source(sourcePath)
                    .build();
        };
    }

    /** This runtime's tracer, bound beside the pools; a hand-built context is a no-op. */
    private static io.tesseraql.core.telemetry.Tracer tracer(Exchange exchange) {
        io.tesseraql.core.telemetry.Tracer bound = exchange.beans().lookup(
                TesseraqlProperties.TRACER_BEAN, io.tesseraql.core.telemetry.Tracer.class);
        return bound != null ? bound : io.tesseraql.core.telemetry.NoopTracer.INSTANCE;
    }

    /** Turns a row-count mismatch into a conflict (or error) instead of a silent lost update. */
    private void checkExpectation(Step step, Integer affected) {
        int actual = affected == null ? 0 : affected;
        if (actual == step.expect().rowCount()) {
            return;
        }
        boolean conflict = "conflict".equals(step.expect().effectiveOnMismatch());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conflict", Map.of(
                "step", step.name(),
                "expectedRows", step.expect().rowCount(),
                "actualRows", actual,
                // A message key: the error renderer localizes it per request locale.
                "hint", "tql.conflict.stale"));
        if (step.locked()) {
            // A sibling of `conflict`, never an entry inside it (docs/edit-conflict.md
            // decision 5): the renderer hands the whole conflict map to the message catalog as
            // the hint's interpolation parameters, so a key named like a placeholder would
            // silently rewrite the sentence.
            details.put("lock", Map.of(
                    "column", lock,
                    "field", io.tesseraql.core.sql.LockBinding.PARAM,
                    "overwriteField", LockBinder.OVERWRITE_FIELD));
        }
        // A declared lock answers its own code, so a client can tell a stale write — which has a
        // dialog and a waiver — from any other row-count expectation that happened to fail. The
        // gate is the step, not the route: a multi-step command whose other step declared its own
        // expect: keeps 4092, or it would offer to waive a predicate that was never the problem.
        TqlErrorCode code = step.locked()
                ? LOCK_CONFLICT
                : (conflict
                        ? EXPECT_CONFLICT
                        : EXPECT_FAILED);
        throw TqlException.builder(code)
                .message("Step '" + step.name() + "' affected " + actual + " row(s), expected "
                        + step.expect().rowCount())
                .source(step.sourcePath())
                .details(details)
                .build();
    }

    /** Classifies a failure, applying the route's declared constraint-to-field mapping. */
    private TqlException asTqlException(Exception ex) {
        if (ex instanceof TqlException tql) {
            return tql;
        }
        SQLException sql = ex instanceof SQLException direct
                ? direct
                : (ex.getCause() instanceof SQLException cause ? cause : null);
        if (sql == null) {
            return new TqlException(TX_ERROR, "Command transaction failed: " + ex.getMessage(),
                    ex);
        }
        SqlErrorKind kind = SqlErrors.classify(sql);
        TqlException.Builder builder = TqlException.builder(code(kind))
                .message("Command transaction failed: " + sql.getMessage())
                .cause(ex);
        List<Map<String, Object>> fields = mapConstraints(sql, kind);
        if (!fields.isEmpty()) {
            builder.details(Map.of("fields", fields));
        }
        return builder.build();
    }

    /** Matches declared constraint names against the violation, yielding field-level errors. */
    private List<Map<String, Object>> mapConstraints(SQLException ex, SqlErrorKind kind) {
        if (errors.constraints().isEmpty()) {
            return List.of();
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> fields = new ArrayList<>();
        errors.constraints().forEach((constraint, mapping) -> {
            if (message.contains(constraint.toLowerCase(Locale.ROOT))) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("field", mapping.field());
                field.put("code", mapping.code() != null ? mapping.code() : defaultCode(kind));
                field.put("constraint", constraint);
                if (mapping.message() != null && !mapping.message().isBlank()) {
                    // A message key (roadmap Phase 22), localized by the error renderer.
                    field.put("message", mapping.message());
                }
                fields.add(field);
            }
        });
        return fields;
    }

    private static String defaultCode(SqlErrorKind kind) {
        return switch (kind) {
            case UNIQUE_VIOLATION -> "duplicate";
            case FOREIGN_KEY_VIOLATION -> "invalid-reference";
            case NOT_NULL_VIOLATION -> "required";
            case CHECK_VIOLATION -> "invalid";
            default -> "constraint-violation";
        };
    }

    private static TqlErrorCode code(SqlErrorKind kind) {
        return switch (kind) {
            case UNIQUE_VIOLATION -> UNIQUE_VIOLATION;
            case FOREIGN_KEY_VIOLATION -> FK_VIOLATION;
            case NOT_NULL_VIOLATION -> NOT_NULL_VIOLATION;
            case CHECK_VIOLATION -> CHECK_VIOLATION;
            case SERIALIZATION_FAILURE -> SERIALIZATION;
            default -> TX_ERROR;
        };
    }

    private void recordExecution(Exchange exchange, Step step, Map<String, Object> result,
            long startNanos, long startedAt) {
        io.tesseraql.core.diag.SqlExecutionLog log = exchange.beans().lookup(
                TesseraqlProperties.SLOW_SQL_LOG_BEAN,
                io.tesseraql.core.diag.SqlExecutionLog.class);
        if (log == null) {
            return;
        }
        Object count = result.containsKey("affectedRows")
                ? result.get("affectedRows")
                : result.get("rowCount");
        long rows = count instanceof Number number ? number.longValue() : 0L;
        log.record(new io.tesseraql.core.diag.SqlExecution(step.sourcePath(), step.mode(),
                (System.nanoTime() - startNanos) / 1_000_000L, rows, startedAt));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
