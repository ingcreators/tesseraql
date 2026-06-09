package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A TesseraQL Simple YAML batch job definition (design ch. 6.1, 6.5).
 *
 * <p>A {@code batch-tasklet} job runs a single {@link #sql} statement; a {@code batch-pipeline}
 * job runs an ordered list of {@link #pipeline} steps.
 *
 * @param version  the DSL version, e.g. {@code tesseraql/v1}
 * @param id       unique job id, e.g. {@code user.dailyMaintenance}
 * @param kind     always {@code job}
 * @param recipe   {@code batch-tasklet} or {@code batch-pipeline}
 * @param trigger  schedule trigger, when present
 * @param params   declared job parameters
 * @param sql      the single statement for a tasklet job
 * @param pipeline  the steps for a pipeline job
 * @param perTenant when true, the job runs once per configured tenant (design ch. 30.3)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobDefinition(
        String version,
        String id,
        String kind,
        String recipe,
        TriggerSpec trigger,
        Map<String, InputField> params,
        SqlBinding sql,
        List<PipelineStep> pipeline,
        boolean perTenant) {

    public JobDefinition {
        params = params == null ? Map.of() : Map.copyOf(params);
        pipeline = pipeline == null ? List.of() : List.copyOf(pipeline);
    }

    /** Returns the steps to run: the explicit pipeline, or a single synthetic step for a tasklet. */
    public List<PipelineStep> effectiveSteps() {
        if (!pipeline.isEmpty()) {
            return pipeline;
        }
        return sql == null ? List.of() : List.of(new PipelineStep("main", sql));
    }
}
