package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A TesseraQL Simple YAML batch job definition (design ch. 6.1, 6.5).
 *
 * <p>A job's work is its {@link #pipeline}: an ordered list of steps, each a binding with an
 * {@code id} plus its output blocks. The single-statement {@code batch-tasklet} spelling is gone
 * with its top-level {@code sql:} key (docs/unified-sources.md decision 8) — one statement is a
 * one-step pipeline, and the surface no longer has a second way to say it.
 *
 * @param version  the DSL version, e.g. {@code tesseraql/v1}
 * @param id       unique job id, e.g. {@code user.dailyMaintenance}
 * @param kind     always {@code job}
 * @param recipe   {@code batch-pipeline}
 * @param trigger  schedule trigger, when present
 * @param input    declared job parameters — the same field contract routes declare
 *                 with {@code input:} (docs/vocabulary-cleanup.md slice 1)
 * @param pipeline the ordered steps this job runs
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
            TriggerSpec trigger, Map<String, InputField> input,
            List<PipelineStep> pipeline, boolean perTenant, ImportSpec fileImport) {
        this(version, id, kind, recipe, datasource, trigger, input, pipeline, perTenant,
                fileImport, null, null);
    }

    /** Convenience constructor for a job on the main datasource (the pre-duckdb shape). */
    public JobDefinition(String version, String id, String kind, String recipe, TriggerSpec trigger,
            Map<String, InputField> input, List<PipelineStep> pipeline,
            boolean perTenant, ImportSpec fileImport) {
        this(version, id, kind, recipe, null, trigger, input, pipeline, perTenant,
                fileImport, null, null);
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

    /** Returns the steps to run — the pipeline, which is all a job's work has ever been. */
    public List<PipelineStep> effectiveSteps() {
        return pipeline;
    }
}
