package io.tesseraql.operations.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.PipelineStep;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a batch job's steps sequentially, persisting lifecycle to the {@link JobRepository}
 * (design ch. 6.5, 26). Each step renders and executes its 2-way SQL; step results are exposed
 * to later steps as {@code steps.<id>.affectedRows}.
 *
 * <p>A {@code notify:} step (roadmap Phase 20) enqueues a notification on the transactional
 * outbox instead of executing SQL, and an optional {@link FailureListener} observes failed
 * executions so the runtime can raise job-failure alerts through the same channels.
 *
 * <p>What a step <em>does</em> is its kind's {@link StepRunner}, reading the {@link StepContext}
 * this class builds for it; what the executor owns is the run around it — the execution record,
 * the ambient context, the cooperative stop, the failure alert, and the dispatch that picks the
 * one runner a step gets.
 */
public final class JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutor.class);
    /** TQL-BATCH-4041: the reserved businessDate parameter is not an ISO date (HTTP 400). */
    private static final TqlErrorCode INVALID_BUSINESS_DATE = new TqlErrorCode(TqlDomain.BATCH,
            4041);

    private int sqlTimeoutSeconds;
    private int maxRows = 10_000;
    private String onOverflow = "fail";

    /** Observes failed job executions (roadmap Phase 20 operations alerts). */
    @FunctionalInterface
    public interface FailureListener {
        void jobFailed(String jobId, String executionId, String appName, String message);
    }

    /** Datasource to dialect id, resolved once: reading the vendor costs a pooled connection. */
    private final Map<DataSource, String> dialects = new java.util.concurrent.ConcurrentHashMap<>();

    private final JobRepository repository;
    private final TempStore tempStore;
    private final io.tesseraql.core.diag.SqlExecutionLog slowSqlLog;
    private final io.tesseraql.core.telemetry.Tracer tracer;
    private final io.tesseraql.core.expr.ExpressionFunctions functions;

    /**
     * Drives the heartbeat of every run this process owns
     * (docs/audit-hardening.md Decision 6).
     *
     * <p>A clock, not a set of boundaries. Writing the pulse where the cooperative stop already
     * polls — step and chunk-commit boundaries — looks free and is wrong: the cadence would be
     * bounded by step duration, so a job whose long step is a single non-chunk statement emits
     * nothing for its whole runtime and a reaper reading that silence kills a live run.
     *
     * <p>One daemon thread for the process. A run that hangs holds a scheduled task, not a
     * thread.
     */
    private final java.util.concurrent.ScheduledExecutorService heartbeats = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tesseraql-job-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /** How often a running execution reports; see {@link #heartbeatInterval}. */
    private java.time.Duration heartbeatInterval = java.time.Duration.ofSeconds(30);

    /**
     * The executions this process is running right now — what a drain has to ask to stop.
     * Tracked here because ownership is a process fact: the repository knows what is RUNNING
     * cluster-wide, and a drain must stop only its own runs, never a neighbour node's.
     */
    private final java.util.Set<String> ownedExecutions = java.util.concurrent.ConcurrentHashMap
            .newKeySet();

    /**
     * The executions a drain asked to stop, each with the drain's reason. The cooperative stop
     * records that reason instead of the operator wording, so a run stopped by a deploy or a
     * shutdown says so — per execution, because the flag on the row cannot say who set it and
     * the reader decides the rerun from what the status says happened
     * (docs/runtime-replace.md, the job drain).
     */
    private final Map<String, String> drainRequested = new java.util.concurrent.ConcurrentHashMap<>();

    /** How long a run may go unheard from before it stops counting as an overlap. */
    private java.time.Duration livenessWindow = java.time.Duration.ofMinutes(5);
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
    private io.tesseraql.operations.outbox.JdbcOutboxStore notificationOutbox;
    private io.tesseraql.operations.http.HttpCallClient httpCallClient;
    private io.tesseraql.core.account.PreferenceStore preferenceStore;
    private FailureListener failureListener;
    private java.util.function.Function<String, io.tesseraql.core.sql.FilePathResolver> filePathResolvers;
    private java.util.function.Function<String, DataSource> connectors;
    private io.tesseraql.core.files.FileTransferService fileTransfers;
    private Path appHome;
    private FilePusher filePusher;
    private io.tesseraql.core.telemetry.Meter meter = io.tesseraql.core.telemetry.NoopMeter.INSTANCE;

    /**
     * Delivers a produced file to a {@code push:} step's target
     * (docs/analytics-experience.md); the runtime wires the push service, this module stays
     * free of it.
     */
    @FunctionalInterface
    public interface FilePusher {
        void push(io.tesseraql.yaml.model.PushSpec spec, String filename,
                java.io.InputStream content);
    }

    public JobExecutor(JobRepository repository, TempStore tempStore) {
        this(repository, tempStore, io.tesseraql.core.diag.NoopSqlExecutionLog.INSTANCE);
    }

    public JobExecutor(JobRepository repository, TempStore tempStore,
            io.tesseraql.core.diag.SqlExecutionLog slowSqlLog) {
        this(repository, tempStore, slowSqlLog, io.tesseraql.core.telemetry.NoopTracer.INSTANCE);
    }

    public JobExecutor(JobRepository repository, TempStore tempStore,
            io.tesseraql.core.diag.SqlExecutionLog slowSqlLog,
            io.tesseraql.core.telemetry.Tracer tracer) {
        this(repository, tempStore, slowSqlLog, tracer,
                io.tesseraql.core.expr.ExpressionFunctions.processDefault());
    }

    /**
     * As {@link #JobExecutor(JobRepository, TempStore,
     * io.tesseraql.core.diag.SqlExecutionLog, io.tesseraql.core.telemetry.Tracer)}, resolving
     * custom calls in step SQL against {@code functions}.
     */
    public JobExecutor(JobRepository repository, TempStore tempStore,
            io.tesseraql.core.diag.SqlExecutionLog slowSqlLog,
            io.tesseraql.core.telemetry.Tracer tracer,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.repository = repository;
        this.tempStore = tempStore;
        this.slowSqlLog = slowSqlLog;
        this.tracer = tracer;
        this.functions = functions;
    }

    /**
     * How often a run this process owns writes its heartbeat.
     *
     * <p>Paired with the liveness window the reaper reads: a window shorter than this interval
     * would reap runs that are alive, which lint refuses as TQL-BATCH-4211.
     */
    public JobExecutor heartbeatInterval(java.time.Duration interval) {
        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            this.heartbeatInterval = interval;
        }
        return this;
    }

    /**
     * How long a run may go unheard from before {@code overlap: skip} stops believing in it.
     *
     * <p>Many heartbeat intervals wide on purpose: a transient database blip costs a pulse or two,
     * and a window barely wider than the interval would turn that into a wrongly-skipped firing.
     */
    public JobExecutor livenessWindow(java.time.Duration window) {
        if (window != null && !window.isZero() && !window.isNegative()) {
            this.livenessWindow = window;
        }
        return this;
    }

    /**
     * Asks every run this process owns to stop cooperatively, recording {@code reason} on the
     * stopped executions — the drain's first act (docs/runtime-replace.md): a run between steps
     * stops before the next one, a chunk step stops at its next committed checkpoint with real
     * counts and an exact resume point, and a run in its final step simply completes. The rerun
     * goes through the existing operator path, deliberately not automatic. Best-effort per run:
     * a request that cannot be written must not stop the drain from asking the rest.
     */
    public void requestDrainStop(String reason) {
        for (String executionId : java.util.List.copyOf(ownedExecutions)) {
            try {
                drainRequested.put(executionId, reason);
                if (repository.requestCancel(executionId)) {
                    LOG.info("Requested a cooperative stop of execution {}: {}", executionId,
                            reason);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Could not request a cooperative stop of execution {}: {}", executionId,
                        ex.getMessage());
            }
        }
    }

    /** Stops the heartbeat thread; the runtime calls this on shutdown. */
    public void close() {
        heartbeats.shutdownNow();
    }

    /**
     * The query timeout every batch statement runs under, in seconds; 0 leaves it unset.
     *
     * <p>There was none. A batch step's SQL ran with whatever the driver defaults to — usually
     * forever — holding a pooled connection while it did, where the same statement on a route or
     * inside a command has been bounded by {@code tesseraql.sql.timeoutSeconds} all along. A job
     * is precisely the place a runaway statement goes unnoticed longest: nobody is waiting for
     * the response.
     */
    public JobExecutor sqlTimeoutSeconds(int seconds) {
        this.sqlTimeoutSeconds = Math.max(0, seconds);
        return this;
    }

    /**
     * The declared connectors, by name, so a batch <em>read</em> step may run on one other than
     * the job's (docs/unified-sources.md decision 19). {@code TQL-YAML-1037} forbids this inside
     * a command's transaction, where the pipeline is one transaction on one connection; a batch
     * step owns its own transaction, so a read-side override splits nothing — the same reasoning
     * that lets a route's read-only named query override today. What it buys is the case the
     * framework could not express at all: extract from one database, load into another.
     */
    public JobExecutor connectors(java.util.function.Function<String, DataSource> byName) {
        this.connectors = byName;
        return this;
    }

    /**
     * The materializing-result bounds a step inherits when it declares none of its own
     * (docs/export-pipeline.md, decision 7) — an {@code export:}'s extraction and a
     * {@code mode: query} step's rows alike, because they are the same bound on the same
     * memory. A job has no request to read configuration from, so the runtime hands them over
     * the way it does the SQL timeout.
     */
    public JobExecutor resultBounds(int maxRows, String onOverflow) {
        this.maxRows = maxRows;
        this.onOverflow = onOverflow == null ? "fail" : onOverflow;
        return this;
    }

    /** Wires the outbox store {@code notify:} steps enqueue on (roadmap Phase 20). */
    public JobExecutor notificationOutbox(
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox) {
        this.notificationOutbox = outbox;
        return this;
    }

    /** Wires the outbound HTTP client an {@code http:} step calls through (roadmap Phase 26). */
    public JobExecutor httpCall(io.tesseraql.operations.http.HttpCallClient client) {
        this.httpCallClient = client;
        return this;
    }

    /**
     * Wires the transfer service {@code export:} steps write through
     * (docs/analytics-experience.md track 3); {@code appHome} is the resource-confinement
     * root export templates may reference. Optional — an export step without the wire fails
     * with a plain message.
     */
    public JobExecutor fileTransfers(io.tesseraql.core.files.FileTransferService transfers,
            Path appHome) {
        this.fileTransfers = transfers;
        this.appHome = appHome;
        return this;
    }

    /** Wires the delivery service {@code push:} steps send through. Optional, like the rest. */
    public JobExecutor filePush(FilePusher pusher) {
        this.filePusher = pusher;
        return this;
    }

    /**
     * Wires the meter every run reports through (docs/jobs.md "Observing runs"): a
     * {@code tesseraql.job.runs} counter tagged job/app/status and a
     * {@code tesseraql.job.duration} histogram, feeding the same Prometheus exposition the
     * route counters ride. Optional — the CLI runs with the no-op.
     */
    public JobExecutor meter(io.tesseraql.core.telemetry.Meter meter) {
        this.meter = meter;
        return this;
    }

    /**
     * Wires the preference store recipient-aware {@code notify:} steps consult (roadmap
     * Phase 48). Optional — without it every notification enqueues, as before.
     */
    public JobExecutor preferenceStore(io.tesseraql.core.account.PreferenceStore store) {
        this.preferenceStore = store;
        return this;
    }

    /** Wires the failure listener raising job-failure alerts (roadmap Phase 20). */
    public JobExecutor onFailure(FailureListener listener) {
        this.failureListener = listener;
        return this;
    }

    /**
     * Wires the per-datasource file-scope resolver factory (docs/duckdb.md), so ETL job SQL on a
     * duckdb datasource can carry {@code ${scope.*}} placeholders. Absent (or for a datasource the
     * factory does not cover) a file placeholder fails loudly with the renderer's reject default.
     */
    public JobExecutor filePathResolvers(
            java.util.function.Function<String, io.tesseraql.core.sql.FilePathResolver> factory) {
        this.filePathResolvers = factory;
        return this;
    }

    /** Runs the job and returns the final execution record (COMPLETED or FAILED). */
    public JobExecution run(JobFile jobFile, DataSource dataSource, String appName,
            Map<String, Object> jobParams, String triggerType, String triggeredBy) {
        return run(jobFile, dataSource, null, appName, jobParams, triggerType, triggeredBy);
    }

    /**
     * Runs the job for a specific tenant (design ch. 30.3). The tenant is published into the step
     * context as {@code tenant} so 2-way SQL can bind {@code tenant.id}, and the caller supplies the
     * tenant's datasource for per-tenant isolation.
     */
    public JobExecution run(JobFile jobFile, DataSource dataSource,
            io.tesseraql.core.tenant.TenantContext tenant, String appName,
            Map<String, Object> jobParams, String triggerType, String triggeredBy) {
        return run(jobFile, dataSource, tenant, appName, jobParams, triggerType, triggeredBy,
                java.util.Set.of());
    }

    /**
     * Runs the job, recording the named pipeline steps as {@code SKIPPED} instead of running
     * them — {@code tesseraql job rerun --from-failed-step} passes the source execution's
     * completed steps (docs/batch-platform.md track D).
     */
    public JobExecution run(JobFile jobFile, DataSource dataSource,
            io.tesseraql.core.tenant.TenantContext tenant, String appName,
            Map<String, Object> jobParams, String triggerType, String triggeredBy,
            java.util.Set<String> skipSteps) {
        JobDefinition job = jobFile.definition();
        java.time.LocalDate businessDate = resolveBusinessDate(jobParams);
        // overlap: skip (docs/batch-platform.md track E): while the previous execution still
        // runs, this firing is recorded SKIPPED naming it — auditable and alertable, not a
        // run. The check is a cheap read; scheduled firings are already serialized by the
        // cluster claim, so the residual race is the concurrent-manual-run window.
        if (job.skipsOverlap()) {
            // A previous run only blocks this firing while its owner is still reporting. It used
            // to block forever: a replica killed mid-run left a RUNNING row that nothing would
            // ever finish, and overlap: skip wedged permanently (docs/audit-hardening.md
            // Decision 6).
            java.util.List<JobExecution> running = repository.findRunning(job.id(), livenessWindow);
            if (!running.isEmpty()) {
                String skippedId = repository.recordSkipped(job.id(), appName, triggerType,
                        businessDate, "skipped: execution " + running.get(0).id()
                                + " is still running (overlap: skip)");
                LOG.info("Job {} firing skipped: execution {} still running", job.id(),
                        running.get(0).id());
                return metered(repository.findExecution(skippedId).orElseThrow());
            }
        }
        String executionId = repository.startExecution(job.id(), appName, triggerType,
                triggeredBy, businessDate, paramsJson(jobParams));
        ownedExecutions.add(executionId);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        Map<String, Object> context = new HashMap<>();
        // One vocabulary across routes and jobs (docs/unified-sources.md decision 11): declared
        // inputs are params.*, step results are steps.<id>.*, so an expression means the same
        // thing in a route, a job, an export template, and a test.
        context.put("params", jobParams == null ? Map.of() : jobParams);
        context.put("steps", stepResults);
        context.put("tenant", tenant);
        // The batch.* ambient binds (docs/batch-platform.md track A): every step's SQL
        // reads the business date the run is FOR — defaulted from the firing's local
        // date, overridden by the reserved businessDate parameter, recorded on the
        // execution so a rerun can reuse it.
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("businessDate", java.sql.Date.valueOf(businessDate));
        batch.put("executionId", executionId);
        context.put("batch", batch);

        // The app attribute drives the ops console's per-app trace scope (design ch. 26.11).
        io.tesseraql.core.telemetry.Span jobSpan = tracer.start("tesseraql.job")
                .attribute("jobId", job.id())
                .attribute("trigger", triggerType);
        if (appName != null) {
            jobSpan.attribute("app", appName);
        }
        if (tenant != null) {
            jobSpan.attribute("tenant", tenant.id());
        }
        io.tesseraql.core.telemetry.SpanContext jobContext = jobSpan.context();
        java.util.concurrent.ScheduledFuture<?> pulse = heartbeats.scheduleAtFixedRate(
                () -> {
                    try {
                        repository.heartbeat(executionId);
                    } catch (RuntimeException ex) {
                        // A missed pulse is not a reason to fail the run it is reporting on. The
                        // reaper's window is many intervals wide precisely so a transient database
                        // blip does not read as a dead owner.
                        LOG.debug("Heartbeat for execution {} failed: {}", executionId,
                                ex.getMessage());
                    }
                },
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            boolean stopped = false;
            for (PipelineStep step : job.effectiveSteps()) {
                // The cooperative stop (docs/jobs.md "Stopping a run"): polled at step
                // boundaries, so remaining steps do not start once an operator asked.
                if (repository.isCancelRequested(executionId)) {
                    stopped = true;
                    break;
                }
                if (skipSteps.contains(step.id())) {
                    // Recorded, not run: the source execution already completed this step.
                    repository.skipStep(repository.startStep(executionId, step.id()));
                    stepResults.put(step.id(), Map.of("skipped", true));
                    continue;
                }
                if (runStepTracked(jobFile, step, dataSource, context, stepResults, executionId,
                        appName, jobContext)) {
                    stopped = true;
                    break;
                }
            }
            if (stopped) {
                // The drain's wording when a drain asked; the operator's when an operator did —
                // what the status row says happened is what the reader decides the rerun from.
                String reason = drainRequested.get(executionId);
                repository.stopExecution(executionId,
                        reason != null ? reason : "stopped by operator (cooperative stop)");
                LOG.info("Job {} execution {} stopped: {}", job.id(), executionId,
                        reason != null ? reason : "by operator");
                return metered(repository.findExecution(executionId).orElseThrow());
            }
            repository.completeExecution(executionId);
            LOG.info("Job {} execution {} completed", job.id(), executionId);
        } catch (RuntimeException ex) {
            jobSpan.recordError(ex);
            repository.failExecution(executionId, ex.getMessage());
            LOG.warn("Job {} execution {} failed: {}", job.id(), executionId, ex.getMessage());
            notifyFailure(job.id(), executionId, appName, ex.getMessage());
        } finally {
            ownedExecutions.remove(executionId);
            drainRequested.remove(executionId);
            pulse.cancel(false);
            jobSpan.end();
        }
        return metered(repository.findExecution(executionId).orElseThrow());
    }

    /**
     * Reports one finished run to the meter (docs/jobs.md "Observing runs"): every outcome —
     * COMPLETED, FAILED, STOPPED, SKIPPED — counts under its own status tag, and completed
     * runs record their duration, so a Grafana panel answers "did tonight's close run, and
     * how long has it been trending".
     */
    private JobExecution metered(JobExecution execution) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("job", execution.jobId());
        tags.put("app", execution.appName() == null ? "" : execution.appName());
        tags.put("status", execution.status().name());
        meter.counter("tesseraql.job.runs").increment(tags);
        if (execution.durationMs() != null) {
            meter.histogram("tesseraql.job.duration").record(execution.durationMs(),
                    Map.of("job", execution.jobId()));
        }
        return execution;
    }

    /**
     * The run's parameters as recorded JSON ({@code tesseraql job rerun} re-binds them), values
     * stringified the way HTTP input arrives. Never fails the run: an unserializable map is
     * recorded as null and a rerun falls back to the business date alone.
     */
    private String paramsJson(Map<String, Object> jobParams) {
        if (jobParams == null || jobParams.isEmpty()) {
            return null;
        }
        try {
            Map<String, String> stringified = new LinkedHashMap<>();
            jobParams.forEach((name, value) -> stringified.put(name,
                    value == null ? null : String.valueOf(value)));
            return mapper.writeValueAsString(stringified);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            LOG.warn("Job parameters not recorded (rerun will reuse the business date only): {}",
                    ex.getMessage());
            return null;
        }
    }

    /**
     * The business date this run is for: the reserved {@code businessDate} parameter (ISO
     * {@code yyyy-MM-dd}) when a manual run or a rerun names one, else the firing's local
     * date. A malformed value fails the run before anything executes.
     */
    private static java.time.LocalDate resolveBusinessDate(Map<String, Object> jobParams) {
        Object declared = jobParams == null ? null : jobParams.get("businessDate");
        if (declared == null) {
            return java.time.LocalDate.now();
        }
        try {
            return java.time.LocalDate.parse(String.valueOf(declared));
        } catch (java.time.format.DateTimeParseException malformed) {
            throw TqlException.builder(INVALID_BUSINESS_DATE)
                    .message("businessDate '" + declared + "' is not an ISO date (yyyy-MM-dd)")
                    .build();
        }
    }

    /** A failing alert must never mask the job failure being reported. */
    private void notifyFailure(String jobId, String executionId, String appName, String message) {
        if (failureListener == null) {
            return;
        }
        try {
            failureListener.jobFailed(jobId, executionId, appName, message);
        } catch (RuntimeException alertFailure) {
            LOG.warn("Job-failure alert for {} execution {} failed: {}", jobId, executionId,
                    alertFailure.getMessage());
        }
    }

    /** Returns true when the step ended at a cooperative-stop boundary (chunk steps). */
    private boolean runStepTracked(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, Map<String, Object> stepResults, String executionId,
            String appName, io.tesseraql.core.telemetry.SpanContext jobContext) {
        String stepExecutionId = repository.startStep(executionId, step.id());
        io.tesseraql.core.telemetry.Span stepSpan = tracer.start("tesseraql.job.step", jobContext)
                .attribute("stepId", step.id());
        io.tesseraql.core.telemetry.SpanContext stepContext = stepSpan.context();
        try {
            StepContext stepInput = stepContext(jobFile, step, dataSource, context, executionId,
                    appName, stepContext);
            Map<String, Object> result = runnerFor(step).run(stepInput);
            result = stepInput.enrichStepRows(result);
            stepResults.put(step.id(), result);
            if (Boolean.TRUE.equals(result.get("stopped"))) {
                // The chunk stopped on a committed checkpoint: counts are real, a rerun
                // for the same business date resumes exactly there.
                repository.stopStep(stepExecutionId, recordedRows(result),
                        ((Number) result.getOrDefault("skipped", 0)).intValue());
                return true;
            }
            repository.completeStep(stepExecutionId, recordedRows(result),
                    ((Number) result.getOrDefault("skipped", 0)).intValue());
            return false;
        } catch (RuntimeException ex) {
            stepSpan.recordError(ex);
            repository.failStep(stepExecutionId, ex.getMessage());
            throw ex;
        } finally {
            stepSpan.end();
        }
    }

    /**
     * The one runner a step gets, in the order the executor has always tried the kinds.
     *
     * <p>The app linter refuses most block combinations at build time ("a step is one
     * executable unit"), so an authored step no longer reaches here declaring two of them; the
     * order stays because the dispatch must still pick exactly one, and because a plain
     * {@code sql:} step is the default every other kind is a departure from.
     */
    private static StepRunner runnerFor(PipelineStep step) {
        if (step.sql() != null && step.sql().isHttp()) {
            return HttpStepRunner::run;
        }
        if (step.notification() != null) {
            return NotifyStepRunner::run;
        }
        if (step.chunk() != null) {
            return ChunkStepRunner::run;
        }
        if (step.export() != null) {
            return ExportStepRunner::run;
        }
        if (step.push() != null) {
            return PushStepRunner::run;
        }
        return SqlStepRunner::run;
    }

    /** The executor's wiring, narrowed to the one step being dispatched. */
    private StepContext stepContext(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, String executionId, String appName,
            io.tesseraql.core.telemetry.SpanContext stepSpan) {
        return new StepContext(
                new StepContext.Services(repository, tempStore, mapper, slowSqlLog, tracer, meter,
                        this::dialectOf, functions),
                new StepContext.Bounds(sqlTimeoutSeconds, maxRows, onOverflow),
                new StepContext.Collaborators(notificationOutbox, httpCallClient, preferenceStore,
                        fileTransfers, appHome, filePusher, filePathResolvers, connectors),
                new StepContext.Invocation(jobFile, step, dataSource, context, executionId,
                        appName, stepSpan));
    }

    /**
     * The row count the execution record carries: what the step wrote, or — for a read — what it
     * produced. A read publishes {@code rowCount} and a write {@code affectedRows}
     * (docs/unified-sources.md decision 10), and the operations console shows one number either
     * way, because an operator asking "how much did this step move" means the same question.
     */
    private static int recordedRows(Map<String, Object> result) {
        Object count = result.get("affectedRows");
        if (count == null) {
            count = result.getOrDefault("rowCount", 0);
        }
        return ((Number) count).intValue();
    }

    /**
     * The datasource's dialect id, resolved once per pool and cached.
     *
     * <p>Reading the vendor asks the pool for a connection, too expensive to repeat per step, and
     * a datasource does not change vendor while the process runs.
     */
    private String dialectOf(DataSource dataSource) {
        return dialects.computeIfAbsent(dataSource,
                pool -> io.tesseraql.core.util.DatabaseVendors.vendor(pool).orElse(""));
    }
}
