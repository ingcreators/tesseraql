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
     * A scheduled trigger. The calendar qualifiers (docs/batch-platform.md track B) follow the
     * daily-consider model: the cron says when to consider a firing, the calendar says whether
     * it counts — a filtered-out firing is skipped silently.
     *
     * @param cron       a cron expression, e.g. {@code "0 0 2 * * ?"}
     * @param fixedDelay a fixed delay between runs, e.g. {@code "5s"}
     * @param calendar   a business-day calendar declared under {@code calendars/}
     * @param runOn      {@code businessDay} (the default), {@code firstBusinessDayOfMonth}, or
     *                   {@code lastBusinessDayOfMonth}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(String cron, String fixedDelay, String calendar, String runOn) {

        /** Convenience constructor for an unqualified schedule (the pre-calendar shape). */
        public Schedule(String cron, String fixedDelay) {
            this(cron, fixedDelay, null, null);
        }
    }
}
