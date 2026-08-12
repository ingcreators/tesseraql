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
 * @param input    declared job parameters — the same field contract routes declare
 *                 with {@code input:} (docs/vocabulary-cleanup.md slice 1)
 * @param sql      the single statement for a tasklet job
 * @param pipeline  the steps for a pipeline job
 * @param perTenant when true, the job runs once per configured tenant (design ch. 30.3)
 * @param fileImport the {@code import:} block of a poll-triggered {@code file-import} job
 *                 (roadmap Phase 26): the runtime feeds every polled file through it
 * @param overlap  what a firing does while the previous execution is still running
 *                 (docs/batch-platform.md track E): {@code skip} (the default) records a
 *                 SKIPPED execution naming the running one, {@code concurrent} runs anyway
 * @param sla      the deadline expectations a periodic check alerts on (alert-only)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobDefinition(
        String version,
        String id,
        String kind,
        String recipe,
        String datasource,
        TriggerSpec trigger,
        Map<String, InputField> input,
        Binding sql,
        List<PipelineStep> pipeline,
        boolean perTenant,
        @com.fasterxml.jackson.annotation.JsonProperty("import") ImportSpec fileImport,
        String overlap,
        SlaSpec sla) {

    public JobDefinition {
        input = input == null ? Map.of() : Map.copyOf(input);
        pipeline = pipeline == null ? List.of() : List.copyOf(pipeline);
    }

    /** Convenience constructor without overlap/SLA declarations (the pre-track-E shape). */
    public JobDefinition(String version, String id, String kind, String recipe, String datasource,
            TriggerSpec trigger, Map<String, InputField> input, Binding sql,
            List<PipelineStep> pipeline, boolean perTenant, ImportSpec fileImport) {
        this(version, id, kind, recipe, datasource, trigger, input, sql, pipeline, perTenant,
                fileImport, null, null);
    }

    /** Convenience constructor for a job on the main datasource (the pre-duckdb shape). */
    public JobDefinition(String version, String id, String kind, String recipe, TriggerSpec trigger,
            Map<String, InputField> input, Binding sql, List<PipelineStep> pipeline,
            boolean perTenant, ImportSpec fileImport) {
        this(version, id, kind, recipe, null, trigger, input, sql, pipeline, perTenant,
                fileImport, null, null);
    }

    /** Convenience constructor for a job without an {@code import:} block (the pre-Phase-26 shape). */
    public JobDefinition(String version, String id, String kind, String recipe, TriggerSpec trigger,
            Map<String, InputField> input, Binding sql, List<PipelineStep> pipeline,
            boolean perTenant) {
        this(version, id, kind, recipe, null, trigger, input, sql, pipeline, perTenant, null,
                null, null);
    }

    /**
     * Whether a firing skips while the previous execution still runs (track E). Skip is the
     * default (docs/contract-bugfixes.md track H): stacking a second run on a late one is
     * nearly always a fault amplifier, and a {@code SKIPPED} execution row stays auditable —
     * a job that is safe to overlap declares {@code overlap: concurrent}.
     */
    public boolean skipsOverlap() {
        return !"concurrent".equals(overlap);
    }

    /** Returns the steps to run: the explicit pipeline, or a single synthetic step for a tasklet. */
    public List<PipelineStep> effectiveSteps() {
        if (!pipeline.isEmpty()) {
            return pipeline;
        }
        return sql == null ? List.of() : List.of(new PipelineStep("main", sql));
    }
}
