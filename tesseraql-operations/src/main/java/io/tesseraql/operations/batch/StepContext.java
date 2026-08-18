package io.tesseraql.operations.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.PipelineStep;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Everything one step's {@link StepRunner} reads, and nothing else: the executor's wiring, the
 * bounds a step runs under, the invocation it was dispatched for, and the few operations every
 * kind shares — rendering a step's 2-way SQL, resolving the connector it runs on, folding its
 * {@code enrich:} references into the rows it produced.
 *
 * <p>A step kind is a small program over this context, which is why the kinds became classes:
 * the executor used to be the only thing that knew how any of them ran, so reading one meant
 * reading all six. What a kind needs is now what it names here — a notify step reads the outbox,
 * a push step the transfer service, and neither can reach the other's wiring by accident.
 *
 * <p>{@link JobExecutor} builds one per step, so the invocation is immutable while the runner
 * uses it; the job {@link #context()} map is deliberately not, because a step publishes
 * {@code chunk.*} and {@code row.*} into it for its own SQL to bind.
 */
final class StepContext {

    /**
     * TQL-BATCH-5002: a step failed (its SQL raised an error), a chunk step exceeded its
     * {@code skipLimit}, or a step is misdeclared.
     */
    static final TqlErrorCode STEP_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5002);

    /** The executor's own collaborators: present always, whatever the step kind. */
    record Services(JobRepository repository, TempStore tempStore, ObjectMapper mapper,
            io.tesseraql.core.diag.SqlExecutionLog slowSqlLog,
            io.tesseraql.core.telemetry.Tracer tracer,
            io.tesseraql.core.telemetry.Meter meter,
            java.util.function.Function<DataSource, String> dialects,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
    }

    /** The bounds every step inherits from the runtime: the SQL timeout and the result caps. */
    record Bounds(int sqlTimeoutSeconds, int maxRows, String onOverflow) {
    }

    /**
     * The optional wiring the runtime supplies through {@link JobExecutor}'s fluent setters;
     * each kind reads the ones it needs and refuses with its own message when they are absent.
     */
    record Collaborators(io.tesseraql.operations.outbox.JdbcOutboxStore notificationOutbox,
            io.tesseraql.operations.http.HttpCallClient httpCall,
            io.tesseraql.core.account.PreferenceStore preferences,
            io.tesseraql.core.files.FileTransferService fileTransfers, Path appHome,
            JobExecutor.FilePusher filePusher,
            java.util.function.Function<String, io.tesseraql.core.sql.FilePathResolver> filePathResolvers,
            java.util.function.Function<String, DataSource> connectors) {
    }

    /** The one step this context was built for, inside the run it belongs to. */
    record Invocation(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, String executionId, String appName,
            io.tesseraql.core.telemetry.SpanContext parentSpan) {
    }

    private final Services services;
    private final Bounds bounds;
    private final Collaborators collaborators;
    private final Invocation invocation;

    StepContext(Services services, Bounds bounds, Collaborators collaborators,
            Invocation invocation) {
        this.services = services;
        this.bounds = bounds;
        this.collaborators = collaborators;
        this.invocation = invocation;
    }

    JobRepository repository() {
        return services.repository();
    }

    TempStore tempStore() {
        return services.tempStore();
    }

    ObjectMapper mapper() {
        return services.mapper();
    }

    io.tesseraql.core.diag.SqlExecutionLog slowSqlLog() {
        return services.slowSqlLog();
    }

    io.tesseraql.core.telemetry.Tracer tracer() {
        return services.tracer();
    }

    io.tesseraql.core.expr.ExpressionFunctions functions() {
        return services.functions();
    }

    int sqlTimeoutSeconds() {
        return bounds.sqlTimeoutSeconds();
    }

    int maxRows() {
        return bounds.maxRows();
    }

    String onOverflow() {
        return bounds.onOverflow();
    }

    io.tesseraql.operations.outbox.JdbcOutboxStore notificationOutbox() {
        return collaborators.notificationOutbox();
    }

    io.tesseraql.operations.http.HttpCallClient httpCall() {
        return collaborators.httpCall();
    }

    io.tesseraql.core.account.PreferenceStore preferences() {
        return collaborators.preferences();
    }

    io.tesseraql.core.files.FileTransferService fileTransfers() {
        return collaborators.fileTransfers();
    }

    Path appHome() {
        return collaborators.appHome();
    }

    JobExecutor.FilePusher filePusher() {
        return collaborators.filePusher();
    }

    JobFile jobFile() {
        return invocation.jobFile();
    }

    PipelineStep step() {
        return invocation.step();
    }

    /** The job's connector; only a read may override it (see {@link #stepDataSource()}). */
    DataSource dataSource() {
        return invocation.dataSource();
    }

    Map<String, Object> context() {
        return invocation.context();
    }

    String executionId() {
        return invocation.executionId();
    }

    String appName() {
        return invocation.appName();
    }

    io.tesseraql.core.telemetry.SpanContext parentSpan() {
        return invocation.parentSpan();
    }

    /**
     * The file-scope resolver a step's SQL renders under (docs/duckdb.md): the job's datasource
     * decides it, so {@code ${scope.*}} placeholders mean the same thing in every step of a job.
     */
    io.tesseraql.core.sql.FilePathResolver filePathResolver() {
        return collaborators.filePathResolvers() == null
                ? io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED
                : collaborators.filePathResolvers()
                        .apply(jobFile().definition().datasource());
    }

    /**
     * The connector a step runs on: its own {@code datasource:} when it declares one, else the
     * job's. Only a read may override — a write on another connector would be a second
     * transaction the executor does not own, which is the doctrine
     * [multi-datasource.md](../../../../../../../docs/multi-datasource.md) states and
     * {@code TQL-YAML-1037} enforces at build time.
     */
    DataSource stepDataSource() {
        return stepDataSource(step(), dataSource());
    }

    private DataSource stepDataSource(PipelineStep step, DataSource jobPool) {
        String declared = step.sql() == null ? null : step.sql().datasource();
        if (declared == null || declared.isBlank()) {
            return jobPool;
        }
        DataSource pool = collaborators.connectors() == null
                ? null
                : collaborators.connectors().apply(declared);
        if (pool == null) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "': datasource '" + declared
                            + "' is not declared under tesseraql.datasources")
                    .build();
        }
        return pool;
    }

    /**
     * The datasource's dialect id, resolved once per pool and cached by the executor.
     *
     * <p>Reading the vendor asks the pool for a connection, too expensive to repeat per step, and
     * a datasource does not change vendor while the process runs.
     */
    String dialectOf(DataSource dataSource) {
        return services.dialects().apply(dataSource);
    }

    /** The dialect variant of a step-owned SQL file, beside the file, for the given pool. */
    Path sqlPath(String file, DataSource dataSource) {
        return io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                jobFile().source().getParent().resolve(file).normalize(), dialectOf(dataSource));
    }

    /** Renders one step-owned 2-way SQL file the way {@link SqlStepRunner} does. */
    BoundSql renderStepSql(io.tesseraql.yaml.model.Binding binding, DataSource dataSource,
            io.tesseraql.core.sql.FilePathResolver filePathResolver) {
        Path sqlPath = sqlPath(binding.file(), dataSource);
        return SqlRenderer.render(
                io.tesseraql.core.sql.Sql2WayParser.parse(read(sqlPath), functions()),
                resolveParams(binding), io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED,
                context(), filePathResolver);
    }

    Map<String, Object> resolveParams(io.tesseraql.yaml.model.Binding binding) {
        EvaluationContext evaluation = new EvaluationContext(context());
        Map<String, Object> params = new LinkedHashMap<>();
        binding.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        // The batch.* ambient namespace (docs/batch-platform.md track A) is seeded the
        // way audit.* is seeded into commands: every step SQL reads the business date
        // without wiring it, and a declared param of the same name still wins. A chunk
        // step's reader and writer additionally read chunk.after and the current row.*
        // (docs/batch-platform.md track C).
        params.putIfAbsent("batch", context().get("batch"));
        if (context().containsKey("chunk")) {
            params.putIfAbsent("chunk", context().get("chunk"));
        }
        if (context().containsKey("row")) {
            params.putIfAbsent("row", context().get("row"));
        }
        return params;
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
    Map<String, Object> enrichStepRows(Map<String, Object> result) {
        PipelineStep step = step();
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
        DataSource pool = stepDataSource();
        List<Map<String, Object>> enriched = enrichWindow(
                enrichments(step.sql().enrich(), dialectOf(pool)), pool,
                (List<Map<String, Object>>) rows);
        Map<String, Object> updated = new LinkedHashMap<>(result);
        updated.put("rows", enriched);
        updated.put("rowCount", enriched.size());
        updated.put("first", enriched.isEmpty() ? null : enriched.get(0));
        return updated;
    }

    /**
     * The references one {@code enrich:} map declares, in authored order — a chunk reader's, or
     * a reading step's own. Both are the rows of an acquisition, so both fold references in the
     * same way; only where the rows came from differs.
     *
     * <p>The algorithm is {@link io.tesseraql.yaml.enrich.KeyedReference}'s — the same one a
     * route and an export run. A batch step is the third surface to enrich, and the first that
     * cannot see the compiler, which is why the algorithm lives in the module that owns
     * {@code EnrichSpec} rather than beside any one caller.
     */
    List<io.tesseraql.yaml.enrich.KeyedReference> enrichments(
            Map<String, io.tesseraql.yaml.model.EnrichSpec> declared, String dialect) {
        List<io.tesseraql.yaml.enrich.KeyedReference> references = new java.util.ArrayList<>();
        declared.forEach((name, spec) -> {
            if (spec.sql() == null) {
                references.add(new io.tesseraql.yaml.enrich.KeyedReference(name, spec,
                        List.of(), null, null, dialect,
                        new io.tesseraql.yaml.enrich.KeyedReference.Bounds(sqlTimeoutSeconds(), -1),
                        io.tesseraql.yaml.http.HttpRows::of));
                return;
            }
            java.nio.file.Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                    jobFile().source().getParent().resolve(spec.sql().file()).normalize(), dialect);
            references.add(new io.tesseraql.yaml.enrich.KeyedReference(name, spec,
                    io.tesseraql.core.sql.Sql2WayParser.parse(read(file), functions()),
                    file.toString(),
                    spec.sql().datasource(), dialect,
                    new io.tesseraql.yaml.enrich.KeyedReference.Bounds(sqlTimeoutSeconds(), -1),
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
    List<Map<String, Object>> enrichWindow(
            List<io.tesseraql.yaml.enrich.KeyedReference> enrichments,
            javax.sql.DataSource dataSource, List<Map<String, Object>> window) {
        if (enrichments.isEmpty()) {
            return window;
        }
        List<Map<String, Object>> rows = window;
        for (io.tesseraql.yaml.enrich.KeyedReference reference : enrichments) {
            try {
                rows = reference.enrich(chunkEnvironment(dataSource), context(), rows);
            } catch (SQLException ex) {
                throw TqlException.builder(STEP_ERROR)
                        .message("Step '" + step().id() + "': enrich '" + reference.name()
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
                return httpCall() == null
                        ? null
                        : new io.tesseraql.yaml.http.OutboundGateway() {
                            @Override
                            public Map<String, Object> call(
                                    io.tesseraql.yaml.model.HttpCallSpec spec,
                                    Map<String, Object> callContext) {
                                return httpCall().call(spec, callContext, null);
                            }

                            @Override
                            public Map<String, Object> call(
                                    io.tesseraql.yaml.model.HttpCallSpec spec,
                                    byte[] body, Map<String, String> headers) {
                                return httpCall().call(spec, body, headers);
                            }
                        };
            }

            @Override
            public void degraded(String enrichment) {
                services.meter().counter("tesseraql.enrich.degraded")
                        .increment(Map.of("enrich", enrichment));
            }
        };
    }

    /**
     * Resolves {@code {dotted.path}} placeholders in an export filename against the job
     * context ({@code batch.businessDate} being the one that matters); an unresolved
     * placeholder renders empty rather than failing the step.
     */
    String interpolate(String template) {
        if (template == null || !template.contains("{")) {
            return template;
        }
        EvaluationContext evaluation = new EvaluationContext(context());
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

    static void bind(PreparedStatement statement, BoundSql bound) throws SQLException {
        for (int i = 0; i < bound.parameters().size(); i++) {
            BoundParameter parameter = bound.parameters().get(i);
            statement.setObject(i + 1, parameter.value());
        }
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
