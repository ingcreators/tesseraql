package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single step in a batch pipeline (design ch. 6.5).
 *
 * <p>A step declares exactly one of {@code sql:} (a SQL execution binding), {@code notify:} (a
 * notification enqueued on the transactional outbox, roadmap Phase 20), {@code http-call:} (a
 * synchronous outbound REST call, roadmap Phase 26), or {@code chunk:} (restartable chunked
 * processing, docs/batch-platform.md track C).
 *
 * @param id           unique step id within the job
 * @param sql          the SQL execution binding for this step
 * @param notification the {@code notify:} declaration of a notification step ("notify" itself
 *                     is not a legal record component: it would hide {@code Object.notify()})
 * @param httpCall     the {@code http-call:} declaration of an outbound REST step (roadmap
 *                     Phase 26)
 * @param chunk        the {@code chunk:} declaration of a reader/writer chunk step
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineStep(String id, SqlBinding sql,
        @JsonProperty("notify") NotifySpec notification,
        @JsonProperty("http-call") HttpCallSpec httpCall,
        ChunkSpec chunk) {

    /** Convenience constructor for a SQL step (the pre-Phase-20 shape). */
    public PipelineStep(String id, SqlBinding sql) {
        this(id, sql, null, null, null);
    }

    /** Convenience constructor for a SQL or notification step (the pre-Phase-26 shape). */
    public PipelineStep(String id, SqlBinding sql, NotifySpec notification) {
        this(id, sql, notification, null, null);
    }

    /** Convenience constructor for a step without a {@code chunk:} body (the pre-chunk shape). */
    public PipelineStep(String id, SqlBinding sql, NotifySpec notification,
            HttpCallSpec httpCall) {
        this(id, sql, notification, httpCall, null);
    }
}
