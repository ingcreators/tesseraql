package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single step in a batch pipeline (design ch. 6.5).
 *
 * <p>A step declares exactly one of {@code sql:} (a SQL execution binding) or {@code notify:}
 * (a notification enqueued on the transactional outbox, roadmap Phase 20); {@code send}/
 * {@code transform} steps are added with the large-data and file integration work.
 *
 * @param id           unique step id within the job
 * @param sql          the SQL execution binding for this step
 * @param notification the {@code notify:} declaration of a notification step ("notify" itself
 *                     is not a legal record component: it would hide {@code Object.notify()})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineStep(String id, SqlBinding sql,
        @JsonProperty("notify") NotifySpec notification) {

    /** Convenience constructor for a SQL step (the pre-Phase-20 shape). */
    public PipelineStep(String id, SqlBinding sql) {
        this(id, sql, null);
    }
}
