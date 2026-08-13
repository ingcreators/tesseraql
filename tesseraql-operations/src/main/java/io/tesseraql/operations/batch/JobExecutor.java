package io.tesseraql.operations.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.PipelineStep;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 */
public final class JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutor.class);
    private static final TqlErrorCode STEP_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5002);
    /** TQL-LD-0001: a query step's result exceeded the row cap — the same code routes raise. */
    private static final TqlErrorCode MATERIALIZATION_OVERFLOW = new TqlErrorCode(TqlDomain.LD, 1);
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
    private final ObjectMapper mapper = new ObjectMapper();
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
     * (docs/analytics-experience.md); the runtime wires the Camel-backed push service, this
     * module stays Camel-free.
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
        this.repository = repository;
        this.tempStore = tempStore;
        this.slowSqlLog = slowSqlLog;
        this.tracer = tracer;
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

    /** The ceiling a step's export declares; whether it applies is the codec's answer. */
    private io.tesseraql.core.files.ExportRowCap declaredExportRowCap(
            io.tesseraql.yaml.model.ExportSpec export) {
        return new io.tesseraql.core.files.ExportRowCap(
                export.maxRows() != null ? export.maxRows() : maxRows,
                export.onOverflow() != null ? export.onOverflow() : onOverflow,
                export.format());
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
            java.util.List<JobExecution> running = repository.findRunning(job.id());
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
                repository.stopExecution(executionId,
                        "stopped by operator (cooperative stop)");
                LOG.info("Job {} execution {} stopped by operator", job.id(), executionId);
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
            Map<String, Object> result;
            if (step.sql() != null && step.sql().isHttp()) {
                result = runHttpStep(step, context, stepContext);
            } else if (step.notification() != null) {
                result = runNotifyStep(jobFile, step, context, appName);
            } else if (step.chunk() != null) {
                result = runChunkStep(jobFile, step, dataSource, context, executionId,
                        stepContext);
            } else if (step.export() != null) {
                result = runExportStep(jobFile, step, dataSource, context, appName);
            } else if (step.push() != null) {
                result = runPushStep(step, context);
            } else {
                result = runStep(jobFile, step, stepDataSource(step, dataSource), context,
                        stepContext);
            }
            result = enrichStepRows(jobFile, step, dataSource, context, result);
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
     * Enqueues the step's notification on the outbox (roadmap Phase 20). The event always goes
     * to the framework's outbox table — not a per-tenant datasource — because the dispatcher of
     * this runtime claims it from there. A skipped guard reports zero affected rows.
     */
    private Map<String, Object> runNotifyStep(JobFile jobFile, PipelineStep step,
            Map<String, Object> context, String appName) {
        if (step.sql() != null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' must declare exactly one of sql: or"
                            + " notify:")
                    .build();
        }
        if (notificationOutbox == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': notify steps need the runtime's outbox"
                            + " store")
                    .build();
        }
        io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification = io.tesseraql.yaml.notify.NotifyEvents
                .compile(jobFile.definition().id(), step.id(), step.notification());
        if (!notification.fires(context)) {
            return Map.of("affectedRows", 0);
        }
        // A recipient-naming notification honors that subject's per-channel opt-out (roadmap
        // Phase 48). Job contexts carry no acting principal, so the untenanted scope applies.
        if (io.tesseraql.yaml.notify.NotifyOptOut.optedOut(notification, context,
                preferenceStore, null)) {
            return Map.of("affectedRows", 0, "optedOut", true);
        }
        String eventId = notificationOutbox.insert(notification.build(context,
                appName == null ? "app" : appName,
                notification.resolveRecipient(context), null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", 1);
        result.put("eventId", eventId);
        return result;
    }

    /**
     * Issues the step's outbound REST call (roadmap Phase 26) and publishes what every read
     * publishes: {@code rows} / {@code rowCount} / {@code first}, plus the call's own
     * {@code status} / {@code body} / {@code headers} (docs/unified-sources.md decision 10).
     * {@code select:} names the part of the response the rows come from, exactly as it does on
     * a route's {@code http:} source — the arm is one mechanism, so it answers one way wherever
     * it is declared. The call is synchronous and observable in the trace tree.
     *
     * <p>{@code mode: query-spool} streams those rows to a spool instead of holding them, so a
     * later {@code chunk:} step loads what an API returned (decision 19a). The gateway buffers
     * the response body either way — the spool bounds what the <em>rest of the job</em> holds,
     * not the call — so a genuinely large export still wants the API's own paging.
     *
     * <p>{@code onError: empty} degrades a failed call to zero rows and an {@code error} entry
     * rather than failing the step, the same declaration a route source makes; without it a
     * failure fails the step, and so the job.
     */
    private Map<String, Object> runHttpStep(PipelineStep step, Map<String, Object> context,
            io.tesseraql.core.telemetry.SpanContext parentContext) {
        if (httpCallClient == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': an http: step needs the runtime's"
                            + " outbound HTTP client")
                    .build();
        }
        io.tesseraql.yaml.model.HttpSourceSpec source = step.sql().http();
        Map<String, Object> response;
        try {
            response = httpCallClient.call(source.call(), context, parentContext);
        } catch (RuntimeException failure) {
            if (!source.degradesToEmpty()) {
                throw failure;
            }
            LOG.warn("Job step {} http: call failed; degrading to zero rows (onError: empty): {}",
                    step.id(), failure.getMessage());
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("rows", List.of());
            degraded.put("rowCount", 0);
            degraded.put("first", null);
            degraded.put("body", null);
            degraded.put("status", 0);
            degraded.put("error", failure.getMessage());
            return degraded;
        }
        Object body = io.tesseraql.yaml.http.HttpRows.select(response.get("body"),
                source.select());
        List<Map<String, Object>> rows = io.tesseraql.yaml.http.HttpRows.rows(body);
        Map<String, Object> result = new LinkedHashMap<>();
        if (source.spools()) {
            SpoolRef ref = spool(step, rows);
            result.put("rowCount", (int) ref.rows());
            result.put("spool", ref);
        } else {
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("first", rows.isEmpty() ? null : rows.get(0));
            result.put("body", body);
        }
        result.put("status", response.get("status"));
        result.put("headers", response.get("headers"));
        return result;
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
     * The connector a step runs on: its own {@code datasource:} when it declares one, else the
     * job's. Only a read may override — a write on another connector would be a second
     * transaction the executor does not own, which is the doctrine
     * [multi-datasource.md](../../../../../../../docs/multi-datasource.md) states and
     * {@code TQL-YAML-1037} enforces at build time.
     */
    private DataSource stepDataSource(PipelineStep step, DataSource jobPool) {
        String declared = step.sql() == null ? null : step.sql().datasource();
        if (declared == null || declared.isBlank()) {
            return jobPool;
        }
        DataSource pool = connectors == null ? null : connectors.apply(declared);
        if (pool == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': datasource '" + declared
                            + "' is not declared under tesseraql.datasources")
                    .build();
        }
        return pool;
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

    private Map<String, Object> runStep(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, io.tesseraql.core.telemetry.SpanContext parentContext) {
        // The dialect variant beside the file, the way every other executor picks it: a step
        // declaring `x.sql` with an `x.postgresql.sql` next to it ran the generic one, silently,
        // because this executor resolved the path itself and never asked.
        String dialect = dialectOf(dataSource);
        Path sqlPath = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                jobFile.source().getParent().resolve(step.sql().file()).normalize(), dialect);
        String source = read(sqlPath);
        Map<String, Object> sqlParams = resolveParams(step.sql(), context);
        // File placeholders (docs/duckdb.md) resolve against the job's datasource; the job
        // context doubles as the resolver context, so a perTenant run's tenant partitions scopes.
        io.tesseraql.core.sql.FilePathResolver filePathResolver = filePathResolvers == null
                ? io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED
                : filePathResolvers.apply(jobFile.definition().datasource());
        BoundSql bound = SqlRenderer.render(io.tesseraql.core.sql.Sql2WayParser.parse(source),
                sqlParams, io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED, context,
                filePathResolver);
        String mode = step.sql().effectiveMode();

        io.tesseraql.core.telemetry.Span span = tracer.start("tesseraql.sql.execute", parentContext)
                .attribute("sqlId", sqlPath.toString())
                .attribute("mode", mode)
                .attribute("stepId", step.id());
        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            if (sqlTimeoutSeconds > 0) {
                statement.setQueryTimeout(sqlTimeoutSeconds);
            }
            bind(statement, bound);
            Map<String, Object> result = switch (mode) {
                case "query-spool" -> spool(statement, dialect);
                case "query" -> query(step, statement, dialect);
                default -> Map.of("affectedRows", statement.executeUpdate());
            };
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            long rows = ((Number) result.getOrDefault("affectedRows",
                    result.getOrDefault("rowCount", 0))).longValue();
            span.attribute("affectedRows", rows);
            slowSqlLog.record(new io.tesseraql.core.diag.SqlExecution(
                    sqlPath.toString(), mode, durationMs, rows, startedAt));
            return result;
        } catch (SQLException ex) {
            TqlException failure = TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' failed: " + ex.getMessage())
                    .source(sqlPath.toString())
                    .cause(ex)
                    .build();
            span.recordError(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    /**
     * Runs an export step (docs/analytics-experience.md track 3): the route recipes' export
     * vocabulary on the job's datasource. The extraction SQL renders exactly like a
     * {@code sql:} step's — dialect variant beside the file, ambient {@code batch.*} binds,
     * file placeholders against the job's datasource — and the transfer service executes it
     * synchronously through the codec into the spool, recording the same execution + transfer
     * rows an HTTP {@code file-export} records. The step publishes {@code transferId},
     * {@code rows}, and {@code filename} to the step context; {@code after:} runs in the
     * extraction transaction (the only timing a step supports).
     */
    private Map<String, Object> runExportStep(JobFile jobFile, PipelineStep step,
            DataSource dataSource, Map<String, Object> context, String appName) {
        if (fileTransfers == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' declares export: but no file-transfer"
                            + " service is wired")
                    .build();
        }
        io.tesseraql.yaml.model.ExportSpec export = step.export();
        io.tesseraql.core.sql.FilePathResolver filePathResolver = filePathResolvers == null
                ? io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED
                : filePathResolvers.apply(jobFile.definition().datasource());
        BoundSql query = renderStepSql(jobFile, step.sql(), dataSource, context,
                filePathResolver);
        BoundSql afterExtract = export.after() == null || export.after().sql() == null
                ? null
                : renderStepSql(jobFile, io.tesseraql.yaml.model.Binding.sql(export.after().sql()),
                        dataSource, context,
                        filePathResolver);
        Path template = export.template() == null
                ? null
                : jobFile.source().getParent().resolve(export.template()).normalize();
        // A job has no request to resolve formatting from, so locale:/timezone: are literals.
        io.tesseraql.core.files.FileWriteSpec writeSpec = export
                .toWriteSpec(template, appHome)
                .withFormatting(export.locale(), export.timezone());
        io.tesseraql.core.files.FileTransferService.InlineResult result = fileTransfers
                .exportInline(new io.tesseraql.core.files.FileTransferService.InlineExport(
                        jobFile.definition().id() + "#" + step.id(), appName,
                        export.format(), writeSpec,
                        interpolate(export.filename(), context), query, afterExtract,
                        declaredExportRowCap(export),
                        // No per-step export queries: a step has one arm and it is the rows; a
                        // template's other data belongs to a job that declares it, which no
                        // pipeline step does yet (docs/unified-sources.md, decision 7).
                        Map.of()),
                        dataSource);
        Map<String, Object> stepResult = new LinkedHashMap<>();
        stepResult.put("affectedRows", (int) result.rows());
        stepResult.put("rows", result.rows());
        stepResult.put("transferId", result.transferId());
        stepResult.put("filename", result.filename());
        return stepResult;
    }

    /**
     * Runs a push step (docs/analytics-experience.md): the {@code file:} path resolves to a
     * transfer id — typically an earlier export step's {@code steps.<id>.transferId} — whose
     * produced file the wired pusher delivers to the target. Reading the transfer counts as
     * its first download (a route-produced transfer's {@code download}-timed follow-up fires),
     * because delivered is downloaded.
     */
    private Map<String, Object> runPushStep(PipelineStep step, Map<String, Object> context) {
        if (filePusher == null || fileTransfers == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' declares push: but no push/transfer"
                            + " service is wired")
                    .build();
        }
        io.tesseraql.yaml.model.PushSpec push = step.push();
        Object resolved = new EvaluationContext(context)
                .resolve(Arrays.asList(push.file().split("\\.")));
        String transferId = resolved == null ? null : String.valueOf(resolved);
        if (transferId == null || transferId.isBlank()) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': push file: '" + push.file()
                            + "' resolved to no transfer id")
                    .build();
        }
        io.tesseraql.core.files.FileTransferService.Download download = fileTransfers
                .download(transferId)
                .orElseThrow(() -> TqlException.builder(STEP_ERROR)
                        .message("Step '" + step.id() + "': transfer " + transferId
                                + " has no downloadable file")
                        .build());
        String filename = push.as() == null || push.as().isBlank()
                ? download.filename()
                : interpolate(push.as(), context);
        filePusher.push(push, filename, download.content());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", 1);
        result.put("transferId", transferId);
        result.put("filename", filename);
        result.put("target", push.effectiveTransport());
        return result;
    }

    /** Renders one step-owned 2-way SQL file the way {@link #runStep} does. */
    private BoundSql renderStepSql(JobFile jobFile, io.tesseraql.yaml.model.Binding binding,
            DataSource dataSource, Map<String, Object> context,
            io.tesseraql.core.sql.FilePathResolver filePathResolver) {
        Path sqlPath = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                jobFile.source().getParent().resolve(binding.file()).normalize(),
                dialectOf(dataSource));
        return SqlRenderer.render(io.tesseraql.core.sql.Sql2WayParser.parse(read(sqlPath)),
                resolveParams(binding, context), io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED,
                context, filePathResolver);
    }

    /**
     * Resolves {@code {dotted.path}} placeholders in an export filename against the job
     * context ({@code batch.businessDate} being the one that matters); an unresolved
     * placeholder renders empty rather than failing the step.
     */
    private static String interpolate(String template, Map<String, Object> context) {
        if (template == null || !template.contains("{")) {
            return template;
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{([\\p{L}\\p{N}_.]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            matcher.appendReplacement(out, java.util.regex.Matcher
                    .quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        return matcher.appendTail(out).toString();
    }

    /**
     * A reading step's own {@code enrich:} folded into its rows before any later step binds them
     * (docs/unified-sources.md decision 5). An enrichment is about the rows, whatever fetched
     * them, so a step's arm gets the same treatment a route source's does — the declaration used
     * to be dropped at parse time, which is the failure mode where the document says one thing
     * and the runtime does another.
     *
     * <p>It runs after the step's own connection is closed, so a reference on the same pool never
     * waits on the connection the step just used.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> enrichStepRows(JobFile jobFile, PipelineStep step,
            DataSource jobPool, Map<String, Object> context, Map<String, Object> result) {
        if (step.sql() == null || step.sql().enrich().isEmpty()) {
            return result;
        }
        if (!(result.get("rows") instanceof List<?> rows)) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' declares enrich: but holds no rows - only"
                            + " a step that reads (mode: query, or an http: call) has rows to"
                            + " fold a reference into")
                    .build();
        }
        DataSource pool = stepDataSource(step, jobPool);
        List<Map<String, Object>> enriched = enrichWindow(
                enrichments(jobFile, step.sql().enrich(), dialectOf(pool)), step, context, pool,
                (List<Map<String, Object>>) rows);
        Map<String, Object> updated = new LinkedHashMap<>(result);
        updated.put("rows", enriched);
        updated.put("rowCount", enriched.size());
        updated.put("first", enriched.isEmpty() ? null : enriched.get(0));
        return updated;
    }

    /**
     * The chunk's {@code enrich:} entries, built once per step (docs/lookups.md, slice 14).
     *
     * <p>The algorithm is {@link io.tesseraql.yaml.enrich.KeyedReference}'s — the same one a
     * route and an export run. A batch step is the third surface to enrich, and the first that
     * cannot see the compiler, which is why the algorithm lives in the module that owns
     * {@code EnrichSpec} rather than beside any one caller.
     */
    private List<io.tesseraql.yaml.enrich.KeyedReference> chunkEnrichments(JobFile jobFile,
            PipelineStep step, io.tesseraql.yaml.model.ChunkSpec chunk, String dialect) {
        return enrichments(jobFile, chunk.enrich(), dialect);
    }

    /**
     * The references one {@code enrich:} map declares, in authored order — a chunk reader's, or
     * a reading step's own. Both are the rows of an acquisition, so both fold references in the
     * same way; only where the rows came from differs.
     */
    private List<io.tesseraql.yaml.enrich.KeyedReference> enrichments(JobFile jobFile,
            Map<String, io.tesseraql.yaml.model.EnrichSpec> declared, String dialect) {
        List<io.tesseraql.yaml.enrich.KeyedReference> references = new java.util.ArrayList<>();
        declared.forEach((name, spec) -> {
            if (spec.sql() == null) {
                references.add(new io.tesseraql.yaml.enrich.KeyedReference(name, spec,
                        List.of(), null, null, dialect,
                        new io.tesseraql.yaml.enrich.KeyedReference.Bounds(sqlTimeoutSeconds, -1),
                        io.tesseraql.yaml.http.HttpRows::of));
                return;
            }
            java.nio.file.Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                    jobFile.source().getParent().resolve(spec.sql().file()).normalize(), dialect);
            references.add(new io.tesseraql.yaml.enrich.KeyedReference(name, spec,
                    io.tesseraql.core.sql.Sql2WayParser.parse(read(file)), file.toString(),
                    spec.sql().datasource(), dialect,
                    new io.tesseraql.yaml.enrich.KeyedReference.Bounds(sqlTimeoutSeconds, -1),
                    io.tesseraql.yaml.http.HttpRows::of));
        });
        return references;
    }

    /**
     * One window of reader rows with every enrichment folded in, in authored order.
     *
     * <p>A reference failure fails the window and the step with it. It is not one row's fault,
     * so it must never be recorded as a skip: {@code tql_job_skips} is the record of rows the
     * <em>writer</em> rejected, and an operator reading it has to be able to trust that.
     */
    private List<Map<String, Object>> enrichWindow(
            List<io.tesseraql.yaml.enrich.KeyedReference> enrichments, PipelineStep step,
            Map<String, Object> context, javax.sql.DataSource dataSource,
            List<Map<String, Object>> window) {
        if (enrichments.isEmpty()) {
            return window;
        }
        List<Map<String, Object>> rows = window;
        for (io.tesseraql.yaml.enrich.KeyedReference reference : enrichments) {
            try {
                rows = reference.enrich(chunkEnvironment(dataSource), context, rows);
            } catch (SQLException ex) {
                throw TqlException.builder(STEP_ERROR)
                        .message("Step '" + step.id() + "': enrich '" + reference.name()
                                + "' failed for " + window.size() + " rows")
                        .cause(ex)
                        .build();
            }
        }
        return rows;
    }

    /** What a reference needs here: the step's connection, no scope, the job pipeline's client. */
    private io.tesseraql.yaml.enrich.KeyedReference.Environment chunkEnvironment(
            javax.sql.DataSource dataSource) {
        return new io.tesseraql.yaml.enrich.KeyedReference.Environment() {
            @Override
            public java.sql.Connection connection(String datasource) throws SQLException {
                // A job runs on one connector; a reference naming another would need a second
                // pool the executor does not own, so it reads on the step's own datasource.
                return dataSource.getConnection();
            }

            @Override
            public io.tesseraql.core.sql.ScopeResolver scopeResolver() {
                // A batch run has no principal, so there is no data scope to render under —
                // the same stance the reader and writer already take.
                return io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
            }

            @Override
            public io.tesseraql.yaml.http.OutboundGateway gateway() {
                return httpCallClient == null
                        ? null
                        : new io.tesseraql.yaml.http.OutboundGateway() {
                            @Override
                            public Map<String, Object> call(
                                    io.tesseraql.yaml.model.HttpCallSpec spec,
                                    Map<String, Object> callContext) {
                                return httpCallClient.call(spec, callContext, null);
                            }

                            @Override
                            public Map<String, Object> call(
                                    io.tesseraql.yaml.model.HttpCallSpec spec,
                                    byte[] body, Map<String, String> headers) {
                                return httpCallClient.call(spec, body, headers);
                            }
                        };
            }

            @Override
            public void degraded(String enrichment) {
                meter.counter("tesseraql.enrich.degraded").increment(Map.of("enrich", enrichment));
            }
        };
    }

    /**
     * Runs a chunk step (docs/batch-platform.md track C): the reader streams its keyset-ordered
     * SELECT on one connection, the writer runs once per row on a second connection committing
     * every {@code commitEvery} handled rows, and each committed chunk checkpoints its last
     * handled key so a rerun for the same business date resumes where the failure stopped.
     *
     * <p>A writer failure on one row rolls back to a per-row savepoint, is recorded in
     * {@code tql_job_skips}, and processing continues — until {@code skipLimit} is exceeded,
     * which discards the uncommitted chunk and fails the step. Skipped rows advance the
     * checkpoint like processed ones: they were handled (recorded), not lost.
     */
    private Map<String, Object> runChunkStep(JobFile jobFile, PipelineStep step,
            DataSource dataSource, Map<String, Object> context, String executionId,
            io.tesseraql.core.telemetry.SpanContext parentContext) {
        io.tesseraql.yaml.model.ChunkSpec chunk = step.chunk();
        boolean spooled = chunk.reader() != null && chunk.reader().isSpool();
        if (chunk.reader() == null || (!spooled && chunk.reader().file() == null)
                || chunk.writer() == null || chunk.writer().file() == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': chunk needs a reader (sql: with a file:,"
                            + " or spool: naming an earlier step's spool) and writer.file")
                    .build();
        }
        String jobId = jobFile.definition().id();
        java.time.LocalDate businessDate = ((java.sql.Date) ((Map<?, ?>) context.get("batch"))
                .get("businessDate")).toLocalDate();
        String after = repository.findCheckpoint(jobId, step.id(), businessDate).orElse(null);
        Map<String, Object> chunkContext = new LinkedHashMap<>();
        chunkContext.put("after", after);
        context.put("chunk", chunkContext);

        String dialect = dialectOf(dataSource);
        Path readerPath = spooled
                ? null
                : io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                        jobFile.source().getParent().resolve(chunk.reader().file()).normalize(),
                        dialect);
        Path writerPath = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                jobFile.source().getParent().resolve(chunk.writer().file()).normalize(), dialect);
        String readerId = spooled ? "spool:" + chunk.reader().spool() : readerPath.toString();
        BoundSql boundReader = spooled
                ? null
                : SqlRenderer.render(
                        io.tesseraql.core.sql.Sql2WayParser.parse(read(readerPath)),
                        resolveParams(chunk.reader(), context),
                        io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED, context,
                        io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED);
        java.util.List<io.tesseraql.core.sql.SqlNode> writerTemplate = io.tesseraql.core.sql.Sql2WayParser
                .parse(read(writerPath));

        List<io.tesseraql.yaml.enrich.KeyedReference> enrichments = chunkEnrichments(jobFile, step,
                chunk, dialect);

        io.tesseraql.core.telemetry.Span span = tracer.start("tesseraql.sql.execute", parentContext)
                .attribute("sqlId", readerId)
                .attribute("mode", "chunk")
                .attribute("stepId", step.id());
        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        int processed = 0;
        int skipped = 0;
        // A spooled reader takes no connection at all: its rows were read once, on the step
        // that produced them, possibly on another datasource entirely.
        try (Connection reader = spooled ? null : dataSource.getConnection();
                Connection writer = dataSource.getConnection()) {
            // A held cursor needs its own transaction (PostgreSQL only streams with autocommit
            // off), and the writer's commit cadence is the whole point of the chunk.
            if (reader != null) {
                reader.setAutoCommit(false);
            }
            writer.setAutoCommit(false);
            Map<String, PreparedStatement> writerStatements = new LinkedHashMap<>();
            try (PreparedStatement select = spooled
                    ? null
                    : reader.prepareStatement(boundReader.sql())) {
                if (select != null) {
                    if (sqlTimeoutSeconds > 0) {
                        select.setQueryTimeout(sqlTimeoutSeconds);
                    }
                    select.setFetchSize(
                            Math.max(100, Math.min(chunk.effectiveCommitEvery(), 1000)));
                    bind(select, boundReader);
                }
                String lastKey = null;
                int sinceCommit = 0;
                boolean stopRequested = false;
                try (ChunkRows rows = spooled
                        ? ChunkRows.of(tempStore, spoolRef(step, chunk, context), mapper)
                        : ChunkRows.of(select.executeQuery(), dialect)) {
                    // The reader is read a window at a time so an enrichment can fold its
                    // reference in before the writer sees a row (docs/lookups.md, slice 14).
                    // With no enrichment the window is one row and the loop is what it was.
                    int window = enrichments.isEmpty()
                            ? 1
                            : enrichments.stream().mapToInt(
                                    io.tesseraql.yaml.enrich.KeyedReference::batchSize).min()
                                    .orElse(1);
                    List<Map<String, Object>> buffered = new java.util.ArrayList<>(window);
                    boolean more = rows.next();
                    while (more && !stopRequested) {
                        buffered.clear();
                        while (buffered.size() < window && more) {
                            buffered.add(rows.row());
                            more = rows.next();
                        }
                        // A reference failure fails the WINDOW, and the step with it. It is not
                        // one row's fault, so it must never be recorded as a skip: tql_job_skips
                        // is the record of rows the writer rejected, and an operator reading it
                        // has to be able to trust that.
                        for (Map<String, Object> row : enrichWindow(enrichments, step, context,
                                dataSource, buffered)) {
                            Object keyValue = keyOf(row, chunk.effectiveKey(), step.id(),
                                    readerId);
                            context.put("row", row);
                            BoundSql boundWriter = SqlRenderer.render(writerTemplate,
                                    resolveParams(chunk.writer(), context),
                                    io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED, context,
                                    io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED);
                            PreparedStatement statement = writerStatements.get(boundWriter.sql());
                            if (statement == null) {
                                statement = writer.prepareStatement(boundWriter.sql());
                                if (sqlTimeoutSeconds > 0) {
                                    statement.setQueryTimeout(sqlTimeoutSeconds);
                                }
                                writerStatements.put(boundWriter.sql(), statement);
                            }
                            java.sql.Savepoint beforeRow = writer.setSavepoint();
                            try {
                                bind(statement, boundWriter);
                                statement.executeUpdate();
                                processed++;
                            } catch (SQLException rowFailure) {
                                // The failed statement may have poisoned the transaction (design
                                // stance: PostgreSQL aborts it) — the savepoint keeps the chunk.
                                writer.rollback(beforeRow);
                                skipped++;
                                repository.recordSkip(executionId, step.id(),
                                        String.valueOf(keyValue), rowFailure.getMessage());
                                if (skipped > chunk.effectiveSkipLimit()) {
                                    writer.rollback();
                                    throw TqlException.builder(STEP_ERROR)
                                            .message("Step '" + step.id() + "' exceeded skipLimit "
                                                    + chunk.effectiveSkipLimit() + " (row "
                                                    + keyValue + ": " + rowFailure.getMessage()
                                                    + ")")
                                            .source(writerPath.toString())
                                            .cause(rowFailure)
                                            .build();
                                }
                            }
                            lastKey = String.valueOf(keyValue);
                            sinceCommit++;
                            if (sinceCommit >= chunk.effectiveCommitEvery()) {
                                writer.commit();
                                repository.saveCheckpoint(jobId, step.id(), businessDate, lastKey);
                                sinceCommit = 0;
                                // The cooperative stop lands exactly on a committed checkpoint:
                                // nothing is lost, and a rerun resumes here.
                                if (repository.isCancelRequested(executionId)) {
                                    stopRequested = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (stopRequested) {
                    writer.rollback(); // nothing pending — the stop happened on a commit
                } else {
                    writer.commit();
                    repository.clearCheckpoint(jobId, step.id(), businessDate);
                }
                if (stopRequested) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("affectedRows", processed);
                    result.put("skipped", skipped);
                    result.put("stopped", true);
                    return result;
                }
            } finally {
                for (PreparedStatement statement : writerStatements.values()) {
                    try {
                        statement.close();
                    } catch (SQLException ignored) {
                        // closing the pooled connection reclaims them regardless
                    }
                }
                context.remove("row");
            }
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            span.attribute("affectedRows", (long) processed);
            span.attribute("skippedRows", (long) skipped);
            slowSqlLog.record(new io.tesseraql.core.diag.SqlExecution(
                    readerId, "chunk", durationMs, processed, startedAt));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affectedRows", processed);
            result.put("skipped", skipped);
            return result;
        } catch (SQLException ex) {
            TqlException failure = TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' failed: " + ex.getMessage())
                    .source(readerId)
                    .cause(ex)
                    .build();
            span.recordError(failure);
            throw failure;
        } catch (TqlException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * The spool an earlier step published, named by a context path (decision 19). A rerun with
     * {@code --from-failed-step} hands the prior spool over unchanged, which is what makes the
     * cross-datasource copy restartable rather than merely repeatable.
     */
    private static io.tesseraql.core.spool.SpoolRef spoolRef(PipelineStep step,
            io.tesseraql.yaml.model.ChunkSpec chunk, Map<String, Object> context) {
        Object resolved = new EvaluationContext(context)
                .resolve(java.util.Arrays.asList(chunk.reader().spool().split("\\.")));
        if (resolved instanceof io.tesseraql.core.spool.SpoolRef ref) {
            return ref;
        }
        throw TqlException.builder(STEP_ERROR)
                .message("Step '" + step.id() + "': reader spool: '" + chunk.reader().spool()
                        + "' does not resolve to a spool — name an earlier step's spool, which"
                        + " only a mode: query-spool step publishes")
                .build();
    }

    /**
     * The checkpoint key of one reader row; a reader that never selects it is misdeclared.
     *
     * <p>One key, exactly: reader rows carry {@link io.tesseraql.core.dialect.ResultRows}
     * -normalized labels now, so {@code chunk.key} names the same label a writer bind does and
     * the tri-case probing that papered over raw Oracle labels is gone.
     */
    private static Object keyOf(Map<String, Object> row, String key, String stepId,
            String readerId) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        throw TqlException.builder(STEP_ERROR)
                .message("Step '" + stepId + "': the reader's rows carry no '" + key
                        + "' column — chunk.key must name a column the reader produces")
                .source(readerId)
                .build();
    }

    /**
     * A {@code mode: query} step publishes the envelope every read publishes —
     * {@code rows} / {@code rowCount} / {@code first} — bounded by the step's
     * {@code materialize.maxRows} or the job's default (docs/unified-sources.md decision 18).
     *
     * <p>It used to drain the {@code ResultSet} into a count and discard the rows, which made
     * "fetch a control value, bind it into later steps" inexpressible for a reason no reader of
     * the document could see: the step existed, its result did not. Counting was memory
     * protection; the bound keeps that protection while the rows become usable, and an extract
     * too large to hold is what {@code query-spool} is for.
     */
    private Map<String, Object> query(PipelineStep step, PreparedStatement statement,
            String dialect) throws SQLException {
        int cap = step.sql().materialize() != null && step.sql().materialize().maxRows() != null
                ? step.sql().materialize().maxRows()
                : maxRows;
        String overflow = step.sql().materialize() != null
                && step.sql().materialize().onOverflow() != null
                        ? step.sql().materialize().onOverflow()
                        : onOverflow;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columns = metaData.getColumnCount();
            while (rs.next()) {
                if (cap >= 0 && rows.size() >= cap) {
                    if (!"warn".equals(overflow)) {
                        throw TqlException.builder(MATERIALIZATION_OVERFLOW)
                                .message("Step '" + step.id() + "': query returned more than "
                                        + cap + " rows; raise materialize.maxRows, or use"
                                        + " mode: query-spool for an extract too large to hold")
                                .build();
                    }
                    LOG.warn("Job step {} query exceeded {} rows; truncating", step.id(), cap);
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= columns; col++) {
                    // The one label answer every JDBC path asks (ResultRows): the same
                    // `select … as total` publishes `total` on Oracle and PostgreSQL alike.
                    row.put(io.tesseraql.core.dialect.ResultRows.label(dialect,
                            metaData.getColumnLabel(col)), rs.getObject(col));
                }
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("rowCount", rows.size());
        result.put("first", rows.isEmpty() ? null : rows.get(0));
        return result;
    }

    /**
     * Streams the result set to a tagged-binary {@link io.tesseraql.core.files.SpooledRows}
     * spool, exposing the {@link SpoolRef} to later steps (ch. 28.6).
     *
     * <p>The encoding is the export pipeline's, for the export pipeline's reason: a JSON round
     * trip is lossy exactly where a SQL extract cares — a decimal's scale, a temporal's type —
     * and a chunk writer binds what comes back. Labels are normalized through
     * {@link io.tesseraql.core.dialect.ResultRows}, so the spooled keys are the ones a writer
     * bind or {@code chunk.key} names on every dialect. HTTP-sourced rows keep JSONL
     * ({@link #spool(PipelineStep, List)}): that data was JSON, so JSON is faithful there.
     */
    private Map<String, Object> spool(PreparedStatement statement, String dialect)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columns = metaData.getColumnCount();
            SpoolRef ref;
            try {
                ref = io.tesseraql.core.files.SpooledRows
                        .drain(tempStore, new java.util.Iterator<Map<String, Object>>() {

                            private Boolean advanced;

                            @Override
                            public boolean hasNext() {
                                if (advanced == null) {
                                    try {
                                        advanced = rs.next();
                                    } catch (SQLException ex) {
                                        throw new UncheckedSqlException(ex);
                                    }
                                }
                                return advanced;
                            }

                            @Override
                            public Map<String, Object> next() {
                                if (!hasNext()) {
                                    throw new java.util.NoSuchElementException();
                                }
                                advanced = null;
                                try {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    for (int col = 1; col <= columns; col++) {
                                        row.put(io.tesseraql.core.dialect.ResultRows.label(
                                                dialect, metaData.getColumnLabel(col)),
                                                rs.getObject(col));
                                    }
                                    return row;
                                } catch (SQLException ex) {
                                    throw new UncheckedSqlException(ex);
                                }
                            }
                        })
                        .ref();
            } catch (UncheckedSqlException ex) {
                throw ex.cause;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            // A spool has a count and a reference, and no `rows` — the point of spooling is that
            // the rows were never held. `rows` means a list on every other surface (decision 10),
            // so publishing the count under that name was the envelope contradicting itself.
            result.put("rowCount", (int) ref.rows());
            result.put("spool", ref);
            return result;
        }
    }

    /** Carries a {@link SQLException} across the drain's iterator boundary, unwrapped above. */
    private static final class UncheckedSqlException extends RuntimeException {

        private final SQLException cause;

        UncheckedSqlException(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /**
     * The JSONL spool an {@code http:} acquisition fills from rows already in hand — the one
     * JSONL write loop left, because the response was JSON and JSON round-trips it faithfully;
     * a SQL extract spools tagged binary ({@link #spool(PreparedStatement, String)}) for the
     * same fidelity reason. A spool reference is the only thing a chunk reader takes, which is
     * what lets one reader load a SQL extract and an API result without knowing the difference
     * (docs/unified-sources.md decision 19a).
     */
    private SpoolRef spool(PipelineStep step, List<Map<String, Object>> rows) {
        SpoolWriter writer = tempStore.createWriter(SpoolKind.JSONL);
        try (writer) {
            for (Map<String, Object> row : rows) {
                writer.write(
                        (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
                writer.incrementRows(1);
            }
        } catch (IOException ex) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': spooling the response failed: "
                            + ex.getMessage())
                    .cause(ex)
                    .build();
        }
        // toRef() is only valid after close, which the try-with-resources performed.
        return writer.toRef();
    }

    private static Map<String, Object> resolveParams(io.tesseraql.yaml.model.Binding binding,
            Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        binding.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        // The batch.* ambient namespace (docs/batch-platform.md track A) is seeded the
        // way audit.* is seeded into commands: every step SQL reads the business date
        // without wiring it, and a declared param of the same name still wins. A chunk
        // step's reader and writer additionally read chunk.after and the current row.*
        // (docs/batch-platform.md track C).
        params.putIfAbsent("batch", context.get("batch"));
        if (context.containsKey("chunk")) {
            params.putIfAbsent("chunk", context.get("chunk"));
        }
        if (context.containsKey("row")) {
            params.putIfAbsent("row", context.get("row"));
        }
        return params;
    }

    private static void bind(PreparedStatement statement, BoundSql bound) throws SQLException {
        for (int i = 0; i < bound.parameters().size(); i++) {
            BoundParameter parameter = bound.parameters().get(i);
            statement.setObject(i + 1, parameter.value());
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
