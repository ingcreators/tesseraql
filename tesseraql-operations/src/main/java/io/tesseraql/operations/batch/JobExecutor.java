package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.PipelineStep;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    public JobExecutor(JobRepository repository) {
        this.repository = repository;
    }

    /** Runs the job and returns the final execution record (COMPLETED or FAILED). */
    public JobExecution run(JobFile jobFile, DataSource dataSource, String appName,
            Map<String, Object> jobParams, String triggerType) {
        JobDefinition job = jobFile.definition();
        String executionId = repository.startExecution(job.id(), appName, triggerType);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        Map<String, Object> context = new HashMap<>();
        context.put("job", jobParams == null ? Map.of() : jobParams);
        context.put("step", stepResults);

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
            int affected = runStep(jobFile, step, dataSource, context);
            stepResults.put(step.id(), Map.of("affectedRows", affected));
            repository.completeStep(stepExecutionId, affected);
        } catch (RuntimeException ex) {
            repository.failStep(stepExecutionId, ex.getMessage());
            throw ex;
        }
    }

    private int runStep(JobFile jobFile, PipelineStep step, DataSource dataSource,
            Map<String, Object> context) {
        Path sqlPath = jobFile.source().getParent().resolve(step.sql().file()).normalize();
        String source = read(sqlPath);
        Map<String, Object> sqlParams = resolveParams(step, context);
        BoundSql bound = SqlRenderer.render(source, sqlParams);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            if ("query".equals(step.sql().effectiveMode())) {
                try (ResultSet rs = statement.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        count++;
                    }
                    return count;
                }
            }
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw TqlException.builder(STEP_ERROR)
                    .message("Step '" + step.id() + "' failed: " + ex.getMessage())
                    .source(sqlPath.toString())
                    .cause(ex)
                    .build();
        }
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
