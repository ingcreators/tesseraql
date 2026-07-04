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
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a batch job's steps sequentially, persisting lifecycle to the {@link JobRepository}
 * (design ch. 6.5, 26). Each step renders and executes its 2-way SQL; step results are exposed
 * to later steps as {@code step.<id>.affectedRows}.
 *
 * <p>A {@code notify:} step (roadmap Phase 20) enqueues a notification on the transactional
 * outbox instead of executing SQL, and an optional {@link FailureListener} observes failed
 * executions so the runtime can raise job-failure alerts through the same channels.
 */
public final class JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutor.class);
    private static final TqlErrorCode STEP_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5002);

    /** Observes failed job executions (roadmap Phase 20 operations alerts). */
    @FunctionalInterface
    public interface FailureListener {
        void jobFailed(String jobId, String executionId, String appName, String message);
    }

    private final JobRepository repository;
    private final TempStore tempStore;
    private final io.tesseraql.core.diag.SqlExecutionLog slowSqlLog;
    private final io.tesseraql.core.telemetry.Tracer tracer;
    private final ObjectMapper mapper = new ObjectMapper();
    private io.tesseraql.operations.outbox.JdbcOutboxStore notificationOutbox;
    private io.tesseraql.operations.http.HttpCallClient httpCallClient;
    private io.tesseraql.core.account.PreferenceStore preferenceStore;
    private FailureListener failureListener;

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

    /** Wires the outbox store {@code notify:} steps enqueue on (roadmap Phase 20). */
    public JobExecutor notificationOutbox(
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox) {
        this.notificationOutbox = outbox;
        return this;
    }

    /** Wires the outbound HTTP client {@code http-call:} steps issue through (roadmap Phase 26). */
    public JobExecutor httpCall(io.tesseraql.operations.http.HttpCallClient client) {
        this.httpCallClient = client;
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

    /** Runs the job and returns the final execution record (COMPLETED or FAILED). */
    public JobExecution run(JobFile jobFile, DataSource dataSource, String appName,
            Map<String, Object> jobParams, String triggerType) {
        return run(jobFile, dataSource, null, appName, jobParams, triggerType);
    }

    /**
     * Runs the job for a specific tenant (design ch. 30.3). The tenant is published into the step
     * context as {@code tenant} so 2-way SQL can bind {@code tenant.id}, and the caller supplies the
     * tenant's datasource for per-tenant isolation.
     */
    public JobExecution run(JobFile jobFile, DataSource dataSource,
            io.tesseraql.core.tenant.TenantContext tenant, String appName,
            Map<String, Object> jobParams, String triggerType) {
        JobDefinition job = jobFile.definition();
        String executionId = repository.startExecution(job.id(), appName, triggerType);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        Map<String, Object> context = new HashMap<>();
        context.put("job", jobParams == null ? Map.of() : jobParams);
        context.put("step", stepResults);
        context.put("tenant", tenant);

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
            for (PipelineStep step : job.effectiveSteps()) {
                runStepTracked(jobFile, step, dataSource, context, stepResults, executionId,
                        appName, jobContext);
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
        return repository.findExecution(executionId).orElseThrow();
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

    private void runStepTracked(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, Map<String, Object> stepResults, String executionId,
            String appName, io.tesseraql.core.telemetry.SpanContext jobContext) {
        String stepExecutionId = repository.startStep(executionId, step.id());
        io.tesseraql.core.telemetry.Span stepSpan = tracer.start("tesseraql.job.step", jobContext)
                .attribute("stepId", step.id());
        io.tesseraql.core.telemetry.SpanContext stepContext = stepSpan.context();
        try {
            Map<String, Object> result;
            if (step.httpCall() != null) {
                result = runHttpStep(step, context, stepContext);
            } else if (step.notification() != null) {
                result = runNotifyStep(jobFile, step, context, appName);
            } else {
                result = runStep(jobFile, step, dataSource, context, stepContext);
            }
            stepResults.put(step.id(), result);
            repository.completeStep(stepExecutionId,
                    ((Number) result.getOrDefault("affectedRows", 0)).intValue());
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
                appName == null ? "app" : appName));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", 1);
        result.put("eventId", eventId);
        return result;
    }

    /**
     * Issues the step's outbound REST call (roadmap Phase 26) and publishes the response as
     * {@code step.<id>.status} / {@code step.<id>.body} for later steps to bind. The call is
     * synchronous and observable in the trace tree; failures fail the step (and so the job).
     */
    private Map<String, Object> runHttpStep(PipelineStep step, Map<String, Object> context,
            io.tesseraql.core.telemetry.SpanContext parentContext) {
        if (step.sql() != null || step.notification() != null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' must declare exactly one of sql:, notify:,"
                            + " or http-call:")
                    .build();
        }
        if (httpCallClient == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': http-call steps need the runtime's"
                            + " outbound HTTP client")
                    .build();
        }
        return httpCallClient.call(step.httpCall(), context, parentContext);
    }

    private Map<String, Object> runStep(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, io.tesseraql.core.telemetry.SpanContext parentContext) {
        Path sqlPath = jobFile.source().getParent().resolve(step.sql().file()).normalize();
        String source = read(sqlPath);
        Map<String, Object> sqlParams = resolveParams(step, context);
        BoundSql bound = SqlRenderer.render(source, sqlParams);
        String mode = step.sql().effectiveMode();

        io.tesseraql.core.telemetry.Span span = tracer.start("tesseraql.sql.execute", parentContext)
                .attribute("sqlId", sqlPath.toString())
                .attribute("mode", mode)
                .attribute("stepId", step.id());
        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            Map<String, Object> result = switch (mode) {
                case "query-spool" -> spool(statement);
                case "query" -> {
                    try (ResultSet rs = statement.executeQuery()) {
                        int count = 0;
                        while (rs.next()) {
                            count++;
                        }
                        yield Map.of("affectedRows", count);
                    }
                }
                default -> Map.of("affectedRows", statement.executeUpdate());
            };
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            long rows = ((Number) result.getOrDefault("affectedRows", 0)).longValue();
            span.attribute("affectedRows", rows);
            slowSqlLog.record(new io.tesseraql.core.diag.SqlExecution(
                    sqlPath.toString(), mode, durationMs, rows, startedAt));
            return result;
        } catch (SQLException | IOException ex) {
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

    /** Streams the result set to a JSONL spool, exposing the SpoolRef to later steps (ch. 28.6). */
    private Map<String, Object> spool(PreparedStatement statement)
            throws SQLException, IOException {
        SpoolWriter writer = tempStore.createWriter(SpoolKind.JSONL);
        try (writer; ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columns = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= columns; col++) {
                    row.put(metaData.getColumnLabel(col), rs.getObject(col));
                }
                writer.write(
                        (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
                writer.incrementRows(1);
            }
        }
        // toRef() is only valid after close, which the try-with-resources performed.
        SpoolRef ref = writer.toRef();
        long rows = ref.rows();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", (int) rows);
        result.put("rows", rows);
        result.put("spool", ref);
        return result;
    }

    private static Map<String, Object> resolveParams(PipelineStep step,
            Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        step.sql().params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
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
