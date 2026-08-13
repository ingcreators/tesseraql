package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single step in a batch pipeline (design ch. 6.5).
 *
 * <p>A step <em>is</em> a binding with an {@code id}, plus the output blocks that act on what it
 * produced (docs/unified-sources.md decision 12). Its keys fall on three axes, and a step
 * declares at least one:
 *
 * <ul>
 *   <li><b>Acquisition or statement</b> — the binding arm ({@code sql:} or {@code http:}), at
 *       most one. The arm sits directly on the step because a step is one unit of work in a
 *       sequence, named by its {@code id}; a route's reads are a namespace, so they are a map of
 *       wrapped bindings (rule 7a). The job-side HTTP step used to be a {@code httpCall:} key of
 *       its own, which is the {@code http} arm wearing a pre-union name — so a job step and a
 *       route source answered to two vocabularies for one mechanism.</li>
 *   <li><b>Output</b> — {@code export:}, {@code push:}, {@code notify:}, any of them, beside the
 *       arm. An output block says how to write rows, never what to read.</li>
 *   <li><b>Processing</b> — {@code chunk:}, at most one, carrying its own {@code reader:} /
 *       {@code writer:} role slots.</li>
 * </ul>
 *
 * <pre>{@code
 * - id: report
 *   sql: { file: report.sql, mode: query }
 *   export: { format: csv, filename: summary.csv }
 * - id: deliver
 *   push: { transport: local, path: outbox }
 * }</pre>
 *
 * @param id           unique step id within the job
 * @param sql          the binding this step executes — the arm authored on the step itself
 * @param notification the {@code notify:} declaration of a notification step ("notify" itself
 *                     is not a legal record component: it would hide {@code Object.notify()})
 * @param chunk        the {@code chunk:} declaration of a reader/writer chunk step
 * @param export       the {@code export:} declaration of a file-producing step — the route
 *                     recipes' export vocabulary, run on the job's datasource. It writes the
 *                     rows the step's own arm read; it never carries an acquisition of its own
 * @param push         the {@code push:} declaration of a file-delivering step — a produced
 *                     transfer sent to a local or SFTP/FTPS drop under the push policy block
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineStep(String id, Binding sql,
        @JsonProperty("notify") NotifySpec notification,
        ChunkSpec chunk,
        ExportSpec export,
        PushSpec push) {

    /**
     * The authoring form: the binding's arms sit on the step, beside its output blocks. Jackson
     * cannot bind the same object to both a nested component and the enclosing record, so the
     * arms are read here and folded into the step's {@link Binding}.
     */
    @JsonCreator
    static PipelineStep of(
            @JsonProperty("id") String id,
            @JsonProperty("sql") Binding.SqlArm sql,
            @JsonProperty("when") String when,
            @JsonProperty("http") HttpSourceSpec http,
            @JsonProperty("notify") NotifySpec notification,
            @JsonProperty("chunk") ChunkSpec chunk,
            @JsonProperty("export") ExportSpec export,
            @JsonProperty("push") PushSpec push) {
        Binding binding = sql == null && http == null && when == null
                ? null
                : binding(sql, http, when);
        return new PipelineStep(id, binding, notification, chunk, export, push);
    }

    private static Binding binding(Binding.SqlArm sql, HttpSourceSpec http, String when) {
        Binding armed = Binding.sql(sql);
        return armed == null
                ? new Binding(null, null, http == null ? null : http.mode(), null, null, http,
                        null, null, null, null, null, null, null, when, null)
                : new Binding(armed.file(), armed.contract(), armed.mode(), armed.params(),
                        armed.service(), http, armed.materialize(), armed.sequence(),
                        armed.keys(), armed.expect(), armed.timeoutSeconds(), armed.datasource(),
                        null, when, null);
    }

    /** Convenience constructor for a SQL step (the pre-Phase-20 shape). */
    public PipelineStep(String id, Binding sql) {
        this(id, sql, null, null, null, null);
    }

    /** Convenience constructor for a SQL or notification step (the pre-Phase-26 shape). */
    public PipelineStep(String id, Binding sql, NotifySpec notification) {
        this(id, sql, notification, null, null, null);
    }

    /** Convenience constructor for a step without an {@code export:} body (the pre-export shape). */
    public PipelineStep(String id, Binding sql, NotifySpec notification, ChunkSpec chunk) {
        this(id, sql, notification, chunk, null, null);
    }

    /** Convenience constructor for a step without a {@code push:} body (the pre-push shape). */
    public PipelineStep(String id, Binding sql, NotifySpec notification, ChunkSpec chunk,
            ExportSpec export) {
        this(id, sql, notification, chunk, export, null);
    }
}
