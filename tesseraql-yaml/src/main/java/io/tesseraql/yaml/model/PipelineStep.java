package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single step in a batch pipeline (design ch. 6.5).
 *
 * <p>A step declares exactly one of {@code sql:} (a SQL execution binding), {@code notify:} (a
 * notification enqueued on the transactional outbox, roadmap Phase 20), {@code http-call:} (a
 * synchronous outbound REST call, roadmap Phase 26), {@code chunk:} (restartable chunked
 * processing, docs/batch-platform.md track C), {@code export:} (a formatted file written
 * through the transfer machinery, docs/analytics-experience.md track 3), or {@code push:}
 * (a produced file delivered to a partner drop, local or SFTP/FTPS).
 *
 * @param id           unique step id within the job
 * @param sql          the SQL execution binding for this step
 * @param notification the {@code notify:} declaration of a notification step ("notify" itself
 *                     is not a legal record component: it would hide {@code Object.notify()})
 * @param httpCall     the {@code http-call:} declaration of an outbound REST step (roadmap
 *                     Phase 26)
 * @param chunk        the {@code chunk:} declaration of a reader/writer chunk step
 * @param export       the {@code export:} declaration of a file-producing step — the route
 *                     recipes' export vocabulary, run on the job's datasource
 * @param push         the {@code push:} declaration of a file-delivering step — a produced
 *                     transfer sent to a local or SFTP/FTPS drop under the push policy block
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineStep(String id, SqlBinding sql,
        @JsonProperty("notify") NotifySpec notification,
        @JsonProperty("http-call") HttpCallSpec httpCall,
        ChunkSpec chunk,
        ExportSpec export,
        PushSpec push) {

    /** Convenience constructor for a SQL step (the pre-Phase-20 shape). */
    public PipelineStep(String id, SqlBinding sql) {
        this(id, sql, null, null, null, null, null);
    }

    /** Convenience constructor for a SQL or notification step (the pre-Phase-26 shape). */
    public PipelineStep(String id, SqlBinding sql, NotifySpec notification) {
        this(id, sql, notification, null, null, null, null);
    }

    /** Convenience constructor for a step without a {@code chunk:} body (the pre-chunk shape). */
    public PipelineStep(String id, SqlBinding sql, NotifySpec notification,
            HttpCallSpec httpCall) {
        this(id, sql, notification, httpCall, null, null, null);
    }

    /** Convenience constructor for a step without an {@code export:} body (the pre-export shape). */
    public PipelineStep(String id, SqlBinding sql, NotifySpec notification,
            HttpCallSpec httpCall, ChunkSpec chunk) {
        this(id, sql, notification, httpCall, chunk, null, null);
    }

    /** Convenience constructor for a step without a {@code push:} body (the pre-push shape). */
    public PipelineStep(String id, SqlBinding sql, NotifySpec notification,
            HttpCallSpec httpCall, ChunkSpec chunk, ExportSpec export) {
        this(id, sql, notification, httpCall, chunk, export, null);
    }
}
