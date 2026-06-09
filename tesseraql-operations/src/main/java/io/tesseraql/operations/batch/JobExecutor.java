package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
public final class JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutor.class);
    private static final TqlErrorCode STEP_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5002);

    private final JobRepository repository;
    private final TempStore tempStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobExecutor(JobRepository repository, TempStore tempStore) {
        this.repository = repository;
        this.tempStore = tempStore;
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

        try {
            for (PipelineStep step : job.effectiveSteps()) {
                runStepTracked(jobFile, step, dataSource, context, stepResults, executionId);
            }
            repository.completeExecution(executionId);
            LOG.info("Job {} execution {} completed", job.id(), executionId);
        } catch (RuntimeException ex) {
            repository.failExecution(executionId, ex.getMessage());
            LOG.warn("Job {} execution {} failed: {}", job.id(), executionId, ex.getMessage());
        }
        return repository.findExecution(executionId).orElseThrow();
    }

    private void runStepTracked(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context, Map<String, Object> stepResults, String executionId) {
        String stepExecutionId = repository.startStep(executionId, step.id());
        try {
            Map<String, Object> result = runStep(jobFile, step, dataSource, context);
            stepResults.put(step.id(), result);
            repository.completeStep(stepExecutionId,
                    ((Number) result.getOrDefault("affectedRows", 0)).intValue());
        } catch (RuntimeException ex) {
            repository.failStep(stepExecutionId, ex.getMessage());
            throw ex;
        }
    }

    private Map<String, Object> runStep(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context) {
        Path sqlPath = jobFile.source().getParent().resolve(step.sql().file()).normalize();
        String source = read(sqlPath);
        Map<String, Object> sqlParams = resolveParams(step, context);
        BoundSql bound = SqlRenderer.render(source, sqlParams);
        String mode = step.sql().effectiveMode();

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            return switch (mode) {
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
        } catch (SQLException | IOException ex) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' failed: " + ex.getMessage())
                    .source(sqlPath.toString())
                    .cause(ex)
                    .build();
        }
    }

    /** Streams the result set to a JSONL spool, exposing the SpoolRef to later steps (ch. 28.6). */
    private Map<String, Object> spool(PreparedStatement statement) throws SQLException, IOException {
        SpoolRef ref;
        long rows;
        try (ResultSet rs = statement.executeQuery();
                SpoolWriter writer = tempStore.createWriter(SpoolKind.JSONL)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columns = metaData.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= columns; col++) {
                    row.put(metaData.getColumnLabel(col), rs.getObject(col));
                }
                writer.write((mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
                writer.incrementRows(1);
            }
            writer.close();
            ref = writer.toRef();
            rows = ref.rows();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", (int) rows);
        result.put("rows", rows);
        result.put("spool", ref);
        return result;
    }

    private static Map<String, Object> resolveParams(PipelineStep step, Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        step.sql().params().forEach((bindName, sourceExpr) ->
                params.put(bindName, evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
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
