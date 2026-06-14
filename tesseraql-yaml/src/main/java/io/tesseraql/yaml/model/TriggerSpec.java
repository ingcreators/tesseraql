package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Job / route trigger declaration (design ch. 6.1, 6.5).
 *
 * @param schedule scheduled trigger for batch jobs
 * @param poll     a directory-polling trigger for a {@code file-import} job (roadmap Phase 26)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggerSpec(Schedule schedule, PollSpec poll) {

    /** Convenience constructor for a scheduled trigger (the pre-Phase-26 shape). */
    public TriggerSpec(Schedule schedule) {
        this(schedule, null);
    }

    /**
     * A scheduled trigger.
     *
     * @param cron       a cron expression, e.g. {@code "0 0 2 * * ?"}
     * @param fixedDelay a fixed delay between runs, e.g. {@code "5s"}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(String cron, String fixedDelay) {
    }
}
