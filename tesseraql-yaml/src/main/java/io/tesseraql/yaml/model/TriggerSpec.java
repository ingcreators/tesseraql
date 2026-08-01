package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Job / route trigger declaration (design ch. 6.1, 6.5).
 *
 * @param schedule scheduled trigger for batch jobs
 * @param poll     a directory-polling trigger for a {@code file-import} job (roadmap Phase 26)
 * @param after    light chaining (docs/batch-platform.md track D): the job fires when the
 *                 named job's execution completes successfully in the same app — enough for
 *                 "extract, then send"; job-net orchestration stays with external schedulers
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggerSpec(Schedule schedule, PollSpec poll, String after) {

    /** Convenience constructor for a scheduled trigger (the pre-Phase-26 shape). */
    public TriggerSpec(Schedule schedule) {
        this(schedule, null, null);
    }

    /** Convenience constructor without chaining (the pre-chaining shape). */
    public TriggerSpec(Schedule schedule, PollSpec poll) {
        this(schedule, poll, null);
    }

    /**
     * A scheduled trigger. The calendar qualifiers (docs/batch-platform.md track B) follow the
     * daily-consider model: the cron says when to consider a firing, the calendar says whether
     * it counts — a filtered-out firing is skipped silently.
     *
     * <p>The nominal-day qualifiers express the shifted monthly date ("the 5th, or the next
     * business day when it is a holiday"): the shift is a pure function of the calendar, so a
     * daily cron plus these two keys needs no scheduler state, and the run's business date
     * defaults to the <em>nominal</em> date — "the 5th's run, executed on the 7th".
     *
     * @param cron       a cron expression, e.g. {@code "0 0 2 * * ?"}
     * @param fixedDelay a fixed delay between runs, e.g. {@code "5s"}
     * @param calendar   a business-day calendar declared under {@code calendars/}
     * @param runOn      {@code businessDay} (the default), {@code firstBusinessDayOfMonth}, or
     *                   {@code lastBusinessDayOfMonth}
     * @param dayOfMonth the nominal day (1–31, rounded down to the month's last day) the
     *                   firing is <em>for</em>; mutually exclusive with {@code runOn}
     * @param shift      where a non-business nominal day moves: {@code nextBusinessDay} (the
     *                   default) or {@code previousBusinessDay}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(String cron, String fixedDelay, String calendar, String runOn,
            Integer dayOfMonth, String shift) {

        /** Convenience constructor for an unqualified schedule (the pre-calendar shape). */
        public Schedule(String cron, String fixedDelay) {
            this(cron, fixedDelay, null, null, null, null);
        }

        /** Convenience constructor without nominal-day qualifiers (the pre-shift shape). */
        public Schedule(String cron, String fixedDelay, String calendar, String runOn) {
            this(cron, fixedDelay, calendar, runOn, null, null);
        }
    }
}
