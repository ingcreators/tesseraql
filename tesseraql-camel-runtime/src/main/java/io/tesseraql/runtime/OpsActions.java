package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The find&rarr;scope&rarr;act cores the operations surface serves from two faces: the JSON
 * API ({@link OperationsRouteBuilder}) and the bundled console's {@code ops.*} service
 * providers (registered in {@code TesseraqlRuntime.start}). Each pair used to duplicate the
 * lookup, the per-application scope gate, and the action; the {@code transferFile}
 * handler was already explicitly shared ("two faces, one handler"), and this class applies
 * the same rule to the rest. Callers keep only what is genuinely surface-specific: how the
 * request's parameters arrive and how the result is shaped — wire maps for the API, view
 * models for the console.
 */
final class OpsActions {

    /**
     * How many recent outbox/queue events either face lists. The console listed 100 while the
     * API returned 200 — an unexplained drift, not a decision — so both read this one window.
     */
    static final int RECENT_LIMIT = 200;

    /**
     * TQL-BATCH-4040: the requested operations resource (job, execution, trace, or event) is
     * unknown — or outside the caller's {@code tql.ops.view.<name>}/{@code tql.ops.run.<name>}
     * scope, which reads the same. Thrown, so the standard error path answers 404 with the
     * framework envelope (the shape {@code ErrorResponseRenderer.httpStatus} always promised for
     * this code).
     */
    private static final io.tesseraql.core.error.TqlErrorCode UNKNOWN = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.BATCH, 4040);

    /** The 404 refusal for an unknown — or out-of-scope, which reads the same — resource. */
    static TqlException notFound(String what) {
        return notFound(what, "tql.ops");
    }

    /** The same 404 shape naming the atom family whose absence made the resource unreachable. */
    static TqlException notFound(String what, String atomFamily) {
        return TqlException.builder(UNKNOWN)
                .message(what + " is unknown or outside the caller's " + atomFamily + " scope")
                .build();
    }

    /**
     * Runs a job by id; decouples the operations surface from the runtime instance. The
     * trigger facts ride along so the execution row records how - and for a manual run, by
     * whom - it started (docs/ops-console-actions.md).
     */
    @FunctionalInterface
    interface JobRunner {
        JobExecution run(String jobId, Map<String, Object> params, String triggerType,
                String triggeredBy);
    }

    private final io.tesseraql.operations.outbox.JdbcOutboxStore outbox;
    private final io.tesseraql.core.messaging.EventChannelStore events;
    private final JobRepository repository;
    private final JobRunner runner;
    /** Job id -> owning app, every declared job (main-app default already applied). */
    private final Map<String, String> jobOwners;
    /** The application this runtime exists to serve. */
    private final String mainApp;
    /** The applications this runtime serves; ops never reports on another runtime's rows. */
    private final java.util.Set<String> servedApps;

    OpsActions(io.tesseraql.operations.outbox.JdbcOutboxStore outbox,
            io.tesseraql.core.messaging.EventChannelStore events, JobRepository repository,
            JobRunner runner, Map<String, String> jobOwners, String mainApp,
            java.util.Set<String> servedApps) {
        this.outbox = outbox;
        this.events = events;
        this.repository = repository;
        this.runner = runner;
        this.jobOwners = java.util.Collections
                .unmodifiableMap(new LinkedHashMap<>(jobOwners));
        this.mainApp = mainApp;
        this.servedApps = java.util.Set.copyOf(servedApps);
    }

    /**
     * The caller's per-app <em>view</em> scope: what this runtime serves, narrowed by the
     * principal's {@code tql.ops.view.<name>} grants (docs/stack-shells.md structural
     * decision 1). The API face passes the bearer principal's permissions; the console face
     * passes the {@code permissions} value its routes bind from the session principal.
     */
    Predicate<String> viewScope(Object permissions) {
        return io.tesseraql.opsui.OpsScope.view(permissions, servedApps);
    }

    /**
     * The caller's per-app <em>run</em> scope — acting, not seeing: run/cancel jobs, redeliver
     * outbox and dead-lettered events. Granted separately ({@code tql.ops.run.<name>}), so an
     * on-call reader is not an acting pen.
     */
    Predicate<String> runScope(Object permissions) {
        return io.tesseraql.opsui.OpsScope.run(permissions, servedApps);
    }

    /** The application this runtime exists to serve — what a runtime-level action acts on. */
    String mainApp() {
        return mainApp;
    }

    /** The most recent outbox events within the caller's scope, newest first. */
    List<io.tesseraql.core.outbox.OutboxEvent> recentOutbox(Predicate<String> scope) {
        return outbox.recent(RECENT_LIMIT).stream()
                .filter(event -> scope.test(event.appName()))
                .toList();
    }

    /** The most recent messaging-channel events within the caller's scope, newest first. */
    List<io.tesseraql.core.messaging.ChannelEvent> recentEvents(Predicate<String> scope) {
        return events.recent(RECENT_LIMIT).stream()
                .filter(event -> scope.test(event.appName()))
                .toList();
    }

    /** Requeues a FAILED/DEAD outbox event; outside the caller's scope it reads as unknown. */
    Map<String, Object> redeliverOutbox(String id, Predicate<String> scope) {
        io.tesseraql.core.outbox.OutboxEvent event = outbox.find(id)
                .filter(found -> scope.test(found.appName()))
                .orElse(null);
        if (event == null) {
            throw notFound("Outbox event '" + id + "'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("redelivered", outbox.redeliver(id));
        return result;
    }

    /** Requeues a DEAD queue message; outside the caller's scope it reads as unknown. */
    Map<String, Object> redeliverEvent(String id, Predicate<String> scope) {
        io.tesseraql.core.messaging.ChannelEvent event = events.find(id)
                .filter(found -> scope.test(found.appName()))
                .orElse(null);
        if (event == null) {
            throw notFound("Queue event '" + id + "'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("redelivered", events.redeliver(id));
        return result;
    }

    /**
     * Starts a manual run of a declared, in-scope job — an unknown job and an out-of-scope one
     * answer the same 404. The parameters come as a supplier so each face keeps its own shaping
     * (the API parses a JSON body, the console strips the {@code param.} form prefix) and its
     * own failure ordering: a body that cannot be shaped still reads as not-found first when
     * the job itself is.
     */
    JobExecution runJob(String jobId, Supplier<Map<String, Object>> params, String triggeredBy,
            Predicate<String> scope) {
        String owner = jobOwners.get(jobId);
        // A job outside the caller's scope is indistinguishable from an unknown one.
        if (owner == null || !scope.test(owner)) {
            throw notFound("Job '" + jobId + "'");
        }
        return runner.run(jobId, params.get(), "manual", triggeredBy);
    }

    /** The execution by id, or null when unknown or outside the caller's scope. */
    JobExecution findExecution(String id, Predicate<String> scope) {
        return repository.findExecution(id)
                .filter(found -> scope.test(found.appName()))
                .orElse(null);
    }
}
