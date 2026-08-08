package io.tesseraql.compiler;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ConcurrencyLimiter;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.binding.HtmlResponseRenderer;
import io.tesseraql.compiler.binding.IdempotencyProcessors;
import io.tesseraql.compiler.binding.JsonResponseRenderer;
import io.tesseraql.compiler.binding.RateLimiter;
import io.tesseraql.compiler.binding.RequestBinder;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ResourceFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.manifest.UiResourceFile;
import io.tesseraql.yaml.model.AdmissionSpec;
import io.tesseraql.yaml.model.IdempotencySpec;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SecuritySpec;
import io.tesseraql.yaml.model.SqlBinding;
import java.nio.file.Path;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.rest.RestDefinition;

/**
 * Compiles a TesseraQL {@link AppManifest} into Camel routes (design ch. 7).
 *
 * <p>The compiler emits an in-memory {@link RouteBuilder} (design decision: in-memory route model
 * for the first milestone) that configures the REST transport and, for each route file, dispatches
 * on the recipe to build the route graph: request binder, {@code tesseraql-sql}, response renderer.
 */
public final class RouteCompiler {

    private static final TqlErrorCode UNSUPPORTED_RECIPE = new TqlErrorCode(TqlDomain.CAMEL, 3100);
    /** TQL-CAMEL-3101: a query-export route declares export blocks only file-export supports. */
    private static final TqlErrorCode INVALID_EXPORT = new TqlErrorCode(TqlDomain.CAMEL, 3101);
    /** TQL-CAMEL-3112: a non-main command transaction cannot carry main-anchored features. */
    private static final TqlErrorCode MAIN_ANCHORED = new TqlErrorCode(TqlDomain.CAMEL, 3112);
    private static final String DEFAULT_DATASOURCE = "main";
    private static final int DEFAULT_MAX_ROWS = 10_000;
    private static final long DEFAULT_IDEMPOTENCY_TTL = java.time.Duration.ofHours(24).toMillis();

    private AppConfig config;
    private io.tesseraql.yaml.config.ResponseHeaderDefaults responseHeaders;
    private AppManifest manifest;
    private java.nio.file.Path compiledAppHome;
    private io.tesseraql.compiler.binding.TenancySettings tenancy;
    private io.tesseraql.yaml.i18n.I18nSettings i18n;
    private io.tesseraql.yaml.webhook.WebhookVerifiers webhookVerifiers;
    private boolean mountRest = true;
    private String appName;

    /**
     * Sets the app name routes are attributed to (e.g. outbox events). Mounted apps share the
     * main app's config, so their name cannot come from {@code tesseraql.app.name} and the host
     * sets it explicitly; unset, the config value applies.
     */
    public RouteCompiler appName(String appName) {
        this.appName = appName;
        return this;
    }

    /** Builds a Camel {@link RouteBuilder} mounting the REST transport and all routes. */
    public RouteBuilder compile(AppManifest manifest) {
        return compile(manifest, true, null);
    }

    /**
     * Builds a {@link RouteBuilder} for the manifest. When {@code mountRest} is false only the
     * {@code direct:} business routes are produced (no REST consumers) — used to hot-reload route
     * bodies in place. When {@code onlyRouteIds} is non-null only those route ids are built
     * (design ch. 16.8 live reload).
     */
    public RouteBuilder compile(AppManifest manifest, boolean mountRest,
            java.util.Set<String> onlyRouteIds) {
        this.config = manifest.config();
        this.manifest = manifest;
        this.compiledAppHome = manifest.appHome();
        this.tenancy = io.tesseraql.compiler.binding.TenancySettings.from(config);
        this.responseHeaders = io.tesseraql.yaml.config.ResponseHeaderDefaults.from(config);
        this.i18n = io.tesseraql.yaml.i18n.I18nSettings.from(config, manifest.appHome());
        this.mountRest = mountRest;
        if (this.appName == null) {
            this.appName = config.getString("tesseraql.app.name").orElse("app");
        }
        return new RouteBuilder() {
            @Override
            public void configure() {
                if (mountRest) {
                    // inlineRoutes is pinned, not inherited (docs/transition-engine.md track E):
                    // it decides whether from(direct:) transition routes keep their own
                    // consumers, and a Camel default flip must not silently rewire the topology
                    // (the dispatch selector's 30s DirectComponent.getConsumer hang).
                    restConfiguration().component("platform-http").inlineRoutes(true);
                }
                // Per-route response.onError steering (HX-Retarget/HX-Reswap), resolved at error
                // time from the failing route id; the error renderer is one shared exception handler.
                java.util.Map<String, io.tesseraql.yaml.model.ResponseSpec.OnError> onErrorByRoute = onErrorByRoute(
                        manifest);
                // The same header block every successful HTML response carries. The error path
                // short-circuits the render that merged it, so an error page and an htmx error
                // fragment - both HTML the browser renders like any other - used to arrive with
                // no CSP and no X-Frame-Options at all.
                java.util.Map<String, String> errorHeaders = responseHeaders.headers();
                onException(TqlException.class).handled(true)
                        .process(new ErrorResponseRenderer(i18n, onErrorByRoute,
                                manifest.appHome(), errorHeaders));
                onException(Exception.class).handled(true)
                        .process(new ErrorResponseRenderer(i18n, onErrorByRoute,
                                manifest.appHome(), errorHeaders));
                for (RouteFile routeFile : manifest.routes()) {
                    if (onlyRouteIds == null
                            || onlyRouteIds.contains(routeFile.definition().id())) {
                        buildRoute(this, manifest.appHome(), routeFile);
                    }
                }
                // Application-declared MCP tools (roadmap Phase 24): each compiles to a direct:
                // route consumed by the runtime's MCP endpoint, never mounted on HTTP.
                for (ToolFile toolFile : manifest.tools()) {
                    if (onlyRouteIds == null
                            || onlyRouteIds.contains(toolFile.definition().id())) {
                        buildMcpTool(this, toolFile);
                    }
                }
                // Application-declared MCP resources (roadmap Phase 24): read-only context, served
                // over the same MCP endpoint; each compiles to a read-only direct: route.
                for (ResourceFile resourceFile : manifest.resources()) {
                    if (onlyRouteIds == null
                            || onlyRouteIds.contains(resourceFile.definition().id())) {
                        buildMcpResource(this, resourceFile);
                    }
                }
                // Application-declared MCP Apps UI resources (roadmap Phase 24): each renders an
                // hc-* fragment, served as a ui:// resource over the same MCP endpoint.
                for (UiResourceFile uiFile : manifest.uiResources()) {
                    if (onlyRouteIds == null
                            || onlyRouteIds.contains(uiFile.definition().id())) {
                        buildMcpUi(this, manifest.appHome(), uiFile);
                    }
                }
                // Messaging consumers (roadmap Phase 27): each queue-consume route compiles to a
                // direct:queue.<id> route the runtime's channel consumer drives, never mounted on
                // HTTP — so they live outside the REST surface, like MCP tools.
                for (RouteFile consumerFile : manifest.consumers()) {
                    if (onlyRouteIds == null
                            || onlyRouteIds.contains(consumerFile.definition().id())) {
                        buildQueueConsume(this, consumerFile);
                    }
                }
                // Approval workflows (roadmap Phase 28): each workflow synthesizes one
                // transactional-command route per transition, mounted on HTTP — the author declares
                // states and transitions, not a route per transition.
                for (io.tesseraql.yaml.manifest.WorkflowFile workflowFile : manifest.workflows()) {
                    buildWorkflow(this, workflowFile, onlyRouteIds);
                }
                // Attachments (roadmap Phase 30): each attachment document synthesizes an off-heap
                // upload route, a list route, and a download route, mounted on HTTP under its
                // basePath — the author declares the owning record and limits, not a route apiece.
                for (io.tesseraql.yaml.manifest.AttachmentFile attachmentFile : manifest
                        .attachments()) {
                    buildAttachment(this, attachmentFile, onlyRouteIds);
                }
            }
        };
    }

    /**
     * Builds an attachment document's three routes (roadmap Phase 30 slice 1): an off-heap multipart
     * upload {@code POST basePath}, a metadata list {@code GET basePath}, and a download
     * {@code GET basePath/{attachmentId}}. Each carries the document's {@code security:}; the owning
     * record key in {@code basePath} scopes list and download to that record.
     */
    private void buildAttachment(RouteBuilder builder,
            io.tesseraql.yaml.manifest.AttachmentFile attachmentFile,
            java.util.Set<String> onlyRouteIds) {
        io.tesseraql.yaml.model.AttachmentDefinition def = attachmentFile.definition();
        String basePath = def.basePath();
        io.tesseraql.yaml.model.AttachmentDefinition.RecordSpec record = def.record();
        String entity = record == null ? null : record.entity();
        String recordKey = record == null ? null : record.key();
        io.tesseraql.yaml.model.AttachmentDefinition.Limits limits = def.limits();
        long maxBytes = limits == null ? 0 : Math.max(0, limits.maxBytesValue());
        java.util.List<String> contentTypes = limits == null
                ? java.util.List.of()
                : limits.contentTypes();
        SecuritySpec security = def.security();
        String idParam = "attachmentId";

        String uploadId = def.id() + ".upload";
        if (onlyRouteIds == null || onlyRouteIds.contains(uploadId)) {
            String direct = "direct:" + uploadId;
            if (mountRest) {
                restEndpoint(builder, "POST", basePath).to(direct);
            }
            ProcessorDefinition<?> route = builder.from(direct).routeId(uploadId);
            applyAttachmentGovernance(route, uploadId, "POST", basePath, security);
            route.process(new io.tesseraql.compiler.binding.AttachmentUploadProcessor(
                    entity, recordKey, def.bucket(), maxBytes, contentTypes));
        }

        String listId = def.id() + ".list";
        if (onlyRouteIds == null || onlyRouteIds.contains(listId)) {
            String direct = "direct:" + listId;
            if (mountRest) {
                restEndpoint(builder, "GET", basePath).to(direct);
            }
            ProcessorDefinition<?> route = builder.from(direct).routeId(listId);
            applyAttachmentGovernance(route, listId, "GET", basePath, security);
            route.process(new io.tesseraql.compiler.binding.AttachmentListProcessor(entity,
                    recordKey));
        }

        String downloadId = def.id() + ".download";
        if (onlyRouteIds == null || onlyRouteIds.contains(downloadId)) {
            String urlPath = basePath + "/{" + idParam + "}";
            String direct = "direct:" + downloadId;
            if (mountRest) {
                restEndpoint(builder, "GET", urlPath).to(direct);
            }
            ProcessorDefinition<?> route = builder.from(direct).routeId(downloadId);
            applyAttachmentGovernance(route, downloadId, "GET", urlPath, security);
            route.process(new io.tesseraql.compiler.binding.AttachmentDownloadProcessor(entity,
                    recordKey, idParam));
        }
    }

    private void buildRoute(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        switch (definition.recipe()) {
            case "query-json", "command-json" -> buildJson(builder, routeFile);
            case "query-html", "page" -> buildTemplatePage(builder, appHome, routeFile);
            case "query-export" -> buildQueryExport(builder, appHome, routeFile);
            case "file-import" -> buildFileImport(builder, routeFile);
            case "file-export" -> buildFileExport(builder, appHome, routeFile);
            case "webhook" -> buildWebhook(builder, routeFile);
            // queue-consume routes live under consume/, compiled from manifest.consumers(), not here.
            // Every designed recipe is implemented, so an unknown one is a typo: fail fast
            // instead of silently dropping the route from the served surface (design ch. 20.14).
            default -> throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': unknown recipe '" + definition.recipe() + "'");
        }
    }

    private void buildJson(RouteBuilder builder, RouteFile routeFile) {
        if (usesTransactionalCommand(routeFile.definition())) {
            buildTransactionalCommand(builder, routeFile);
            return;
        }
        ProcessorDefinition<?> route = applySessionRotation(
                pipelineThroughSql(builder, routeFile), routeFile.definition())
                .process(responseRenderer(routeFile.definition()));
        applyHttpCache(route, routeFile.definition());
        applyIdempotencyComplete(route, routeFile.definition());
    }

    /** Declarative HTTP caching for query responses (docs/response-shaping.md). */
    private void applyHttpCache(ProcessorDefinition<?> route, RouteDefinition definition) {
        if (definition.cache() != null) {
            route.process(new io.tesseraql.compiler.binding.HttpCacheProcessor(
                    definition.cache()));
        }
    }

    /** Each route's {@code response.onError} steering, keyed by route id (htmx error retarget). */
    private static java.util.Map<String, io.tesseraql.yaml.model.ResponseSpec.OnError> onErrorByRoute(
            AppManifest manifest) {
        java.util.Map<String, io.tesseraql.yaml.model.ResponseSpec.OnError> map = new java.util.LinkedHashMap<>();
        for (RouteFile routeFile : manifest.routes()) {
            RouteDefinition definition = routeFile.definition();
            if (definition.response() != null && definition.response().onError() != null) {
                map.put(definition.id(), definition.response().onError());
            }
        }
        return map;
    }

    /**
     * Whether the route runs through the transactional command processor (roadmap Phase 18):
     * any route declaring an outbox event, command steps, validation rules (Phase 19), or
     * notifications (Phase 20), and every file-based command-json route — so audit binds,
     * row-count expectations, constraint mapping, and declarative validation apply uniformly.
     * Contract/service-bound command routes keep the standard execution pipeline (and fail fast
     * when they declare validate:).
     */
    private static boolean usesTransactionalCommand(RouteDefinition definition) {
        return definition.outbox() != null
                || !definition.steps().isEmpty()
                || !definition.validate().isEmpty()
                || !definition.notifications().isEmpty()
                || ("command-json".equals(definition.recipe())
                        && definition.sql() != null && definition.sql().file() != null);
    }

    /**
     * The declared in-place session rotation (docs/session-rotation.md), appended after
     * successful execution and before the response renders — an execution error diverts
     * to the error renderer first, so a failed command never half-rotates. Session
     * mechanics stay in the auth component beside authenticate/authorize.
     */
    private ProcessorDefinition<?> applySessionRotation(ProcessorDefinition<?> route,
            RouteDefinition definition) {
        if (definition.response() != null && definition.response().session() != null
                && definition.response().session().rotates()) {
            return route.to("tesseraql-auth:rotate");
        }
        return route;
    }

    /** The terminal renderer: a redirect when declared, otherwise the JSON response. */
    private org.apache.camel.Processor responseRenderer(RouteDefinition definition) {
        if (definition.response() != null && definition.response().redirect() != null) {
            return new io.tesseraql.compiler.binding.RedirectRenderer(
                    definition.response().redirect());
        }
        return new JsonResponseRenderer(definition.response().json());
    }

    /**
     * Builds a command route through the transactional command processor (design ch. 39.2,
     * roadmap Phase 18): its SQL steps, document-sequence allocations, and outbox event commit
     * atomically in one transaction. Dialect-specific SQL variants resolve per step, like the
     * standard execution pipeline.
     */
    private void buildTransactionalCommand(RouteBuilder builder, RouteFile routeFile) {
        buildTransactionalCommand(builder, routeFile, null, null);
    }

    private void buildTransactionalCommand(RouteBuilder builder, RouteFile routeFile,
            org.apache.camel.Processor preCommand) {
        buildTransactionalCommand(builder, routeFile, preCommand, null);
    }

    /**
     * Builds the transactional command pipeline, optionally inserting {@code preCommand} after the
     * common steps and before request binding — the inbound webhook recipe (roadmap Phase 26) uses
     * it to verify the signed, replay-protected delivery before a single row is written. A
     * {@code workflow} binding (roadmap Phase 28) makes the command a workflow transition: the
     * processor advances the document's state, checks the guard, and appends history in the same
     * transaction.
     */
    private void buildTransactionalCommand(RouteBuilder builder, RouteFile routeFile,
            org.apache.camel.Processor preCommand,
            io.tesseraql.compiler.binding.WorkflowBinding workflow) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;
        if (mountRest) {
            restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);
        }

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, routeFile);
        applyIdempotencyBegin(route, definition);
        if (preCommand != null) {
            route.process(preCommand);
        }
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, pathParams(routeFile.urlPath()),
                        compiledAppHome))
                .process(commandProcessor(routeFile, workflow));
        // Live-view topics broadcast only after a successful commit: an exception in the
        // command processor (rollback) bypasses this step (docs/realtime.md).
        if (!definition.emit().isEmpty()) {
            step = step.process(new io.tesseraql.compiler.binding.TopicEmitProcessor(
                    definition.emit()));
        }
        // Named queries still run after the command (outside its transaction), in authored order.
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(routeFile, entry.getValue(), entry.getKey()));
        }
        applySessionRotation(step, definition).process(responseRenderer(definition));
        applyIdempotencyComplete(step, definition);
    }

    /**
     * The transactional command processor a route (or a dispatch attempt — the selector
     * invokes members' processors directly, docs/transition-engine.md track B) runs: one
     * construction path, so a member fired through a dispatch is the same pipeline its own
     * REST endpoint runs.
     */
    private io.tesseraql.compiler.binding.TransactionalCommandProcessor commandProcessor(
            RouteFile routeFile, io.tesseraql.compiler.binding.WorkflowBinding workflow) {
        RouteDefinition definition = routeFile.definition();
        Path routeDir = routeFile.source().getParent();
        String datasource = definition.effectiveDatasource();
        requirePlainSqlOffMain(definition);
        String dialect = datasourceDialect(datasource);
        java.util.function.Function<String, Path> stepFile = file -> io.tesseraql.core.dialect.DialectSqlResolver
                .resolve(routeDir.resolve(file).normalize(), dialect);
        return new io.tesseraql.compiler.binding.TransactionalCommandProcessor(
                definition.id(), definition.sql(), definition.steps(), definition.validate(),
                definition.decide(), definition.notifications(), stepFile, datasource,
                dialect, definition.outbox(), definition.publish(), definition.errors(),
                appName, workflow, commandBounds());
    }

    /**
     * Builds an approval workflow (roadmap Phase 28): one transactional-command route per
     * transition. State lives in the managed {@code tql_workflow_instance} table or, in app mode, in
     * a column on the business table — selected per workflow, defaulting to the app-wide
     * {@code tesseraql.workflow.mode}.
     */
    private void buildWorkflow(RouteBuilder builder,
            io.tesseraql.yaml.manifest.WorkflowFile workflowFile,
            java.util.Set<String> onlyRouteIds) {
        io.tesseraql.yaml.model.WorkflowDefinition def = workflowFile.definition();
        if (def.document() == null) {
            throw new TqlException(UNSUPPORTED_RECIPE,
                    "Workflow '" + def.id() + "': a document is required");
        }
        boolean managed = workflowManaged(def);
        String basePath = workflowBasePath(def);
        io.tesseraql.core.workflow.WorkflowStore appStore = managed
                ? null
                : new io.tesseraql.yaml.workflow.ColumnWorkflowStore(def.document().table(),
                        def.document().key(), def.document().stateColumn());
        for (io.tesseraql.yaml.model.TransitionSpec transition : def.transitions()) {
            String routeId = def.id() + "." + transition.id();
            if (onlyRouteIds != null && !onlyRouteIds.contains(routeId)) {
                continue;
            }
            buildTransactionalCommand(builder,
                    transitionRouteFile(workflowFile, def, transition, basePath),
                    null, transitionBinding(workflowFile, def, transition, managed, appStore));
        }
        // One-action dispatches, engine-level (docs/transition-engine.md track B): a
        // governed route carrying the members' shared security spec (the 3112 lint
        // guarantees one audience) whose selector invokes each member's own command
        // processor in order — the full pipeline per attempt, typed 3201/3202
        // fall-through, no shadow routes.
        for (io.tesseraql.yaml.model.DispatchSpec dispatch : def.dispatch()) {
            String routeId = def.id() + "." + dispatch.id();
            if (onlyRouteIds != null && !onlyRouteIds.contains(routeId)) {
                continue;
            }
            String urlPath = basePath + "/{key}/" + dispatch.id();
            io.tesseraql.yaml.model.SecuritySpec security = def.security();
            java.util.List<io.tesseraql.compiler.binding.WorkflowDispatchProcessor.Member> members = new java.util.ArrayList<>();
            for (String memberId : dispatch.oneOf()) {
                io.tesseraql.yaml.model.TransitionSpec member = def.transitions().stream()
                        .filter(t -> memberId.equals(t.id())).findFirst().orElse(null);
                if (member == null) {
                    // The 3112 lint names the unknown member; the endpoint still mounts so
                    // the remaining members serve.
                    continue;
                }
                if (members.isEmpty()) {
                    security = member.security() != null ? member.security() : def.security();
                }
                members.add(new io.tesseraql.compiler.binding.WorkflowDispatchProcessor.Member(
                        memberId, commandProcessor(
                                transitionRouteFile(workflowFile, def, member, basePath),
                                transitionBinding(workflowFile, def, member, managed,
                                        appStore))));
            }
            RouteDefinition definition = new RouteDefinition("tesseraql/v1", routeId, "route",
                    "command-json", java.util.Map.of(), null, security, null, null, null, null,
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    java.util.Map.of(), java.util.Map.of(), null, null, null, null, null, null,
                    dispatchResponse(), null, null, null, null, null);
            String direct = "direct:" + routeId;
            if (mountRest) {
                restEndpoint(builder, "POST", urlPath).to(direct);
            }
            String dialect = datasourceDialect(DEFAULT_DATASOURCE);
            ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
            applyCommonGovernance(route, routeId, "POST", urlPath, definition);
            route.process(new RequestBinder(definition, pathParams(urlPath), compiledAppHome))
                    .process(new io.tesseraql.compiler.binding.WorkflowDispatchProcessor(
                            def.id(), dispatch.id(), members,
                            io.tesseraql.yaml.decision.DecisionSets.compileUses(
                                    dispatch.decide(), dialect),
                            def.document().table(), def.document().key(), dialect,
                            DEFAULT_DATASOURCE, commandBounds() == null
                                    ? 0
                                    : commandBounds().timeoutSeconds()))
                    .process(responseRenderer(definition));
        }
        buildWorkflowDelegate(builder, def, basePath, onlyRouteIds);
    }

    /** The synthesized route file a transition compiles to (roadmap Phase 28). */
    private RouteFile transitionRouteFile(io.tesseraql.yaml.manifest.WorkflowFile workflowFile,
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.yaml.model.TransitionSpec transition, String basePath) {
        io.tesseraql.yaml.model.SqlBinding command = transition.command() == null
                ? null
                : new io.tesseraql.yaml.model.SqlBinding(transition.command(), null, "update",
                        commandParams(transition), null, null, null, null, null);
        io.tesseraql.yaml.model.SecuritySpec security = transition.security() != null
                ? transition.security()
                : def.security();
        return new RouteFile("POST", basePath + "/{key}/" + transition.id(),
                workflowFile.source(),
                synthesizedTransition(def.id() + "." + transition.id(), security, command,
                        transition));
    }

    /**
     * The workflow binding a transition route carries: the executor-compiled pipeline
     * (docs/transition-engine.md — guard parsing in both forms, stamp-column validation, the
     * decide: compile) plus the route-flavored assign/deadline/reminder collaborators.
     */
    private io.tesseraql.compiler.binding.WorkflowBinding transitionBinding(
            io.tesseraql.yaml.manifest.WorkflowFile workflowFile,
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.yaml.model.TransitionSpec transition, boolean managed,
            io.tesseraql.core.workflow.WorkflowStore appStore) {
        return new io.tesseraql.compiler.binding.WorkflowBinding(
                io.tesseraql.yaml.workflow.TransitionExecutor.compile(def, transition, managed,
                        datasourceDialect(DEFAULT_DATASOURCE), workflowFile.source().getParent()),
                "path.key",
                appStore, compileAssign(workflowFile, transition),
                transition.assign() == null
                        ? java.util.Map.of()
                        : transition.assign().params(),
                deadlineMillis(def, transition.to()), assignNotify(def));
    }

    /** The dispatch response: the member outcome plus which member fired. */
    private static io.tesseraql.yaml.model.ResponseSpec dispatchResponse() {
        return new io.tesseraql.yaml.model.ResponseSpec(
                new io.tesseraql.yaml.model.ResponseSpec.JsonResponse(200,
                        java.util.Map.of("ok", Boolean.TRUE, "transition",
                                "dispatch.transition"),
                        null, null, null),
                null, null, null, null, null, null);
    }

    /** The command-json route a workflow transition compiles to (roadmap Phase 28). */
    private RouteDefinition synthesizedTransition(String routeId,
            io.tesseraql.yaml.model.SecuritySpec security,
            io.tesseraql.yaml.model.SqlBinding command,
            io.tesseraql.yaml.model.TransitionSpec transition) {
        return new RouteDefinition("tesseraql/v1", routeId, "route",
                "command-json", java.util.Map.of(), null, security, null, null, null, command,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                transition.decide(), java.util.Map.of(), null, null, null, null, null, null,
                workflowResponse(), null, null, null, null, null);
    }

    /** The compiled task-assignment reminder (Phase 20 channels), or {@code null} when undeclared. */
    private static io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify assignNotify(
            io.tesseraql.yaml.model.WorkflowDefinition def) {
        if (def.reminders() == null || def.reminders().assigned() == null) {
            return null;
        }
        return io.tesseraql.yaml.notify.NotifyEvents.compile(def.id(), "assigned",
                def.reminders().assigned());
    }

    /**
     * Builds the built-in delegation endpoint for a workflow that uses tasks (roadmap Phase 28
     * slice 3): {@code POST {basePath}/{key}/delegate/{to}} reassigns the caller's open task to the
     * delegate, who then sees it in their inbox. Only the current assignee may delegate.
     */
    private void buildWorkflowDelegate(RouteBuilder builder,
            io.tesseraql.yaml.model.WorkflowDefinition def, String basePath,
            java.util.Set<String> onlyRouteIds) {
        boolean usesTasks = def.transitions().stream()
                .anyMatch(t -> t.assign() != null && t.assign().file() != null);
        if (!usesTasks) {
            return;
        }
        String routeId = def.id() + ".delegate";
        if (onlyRouteIds != null && !onlyRouteIds.contains(routeId)) {
            return;
        }
        String direct = "direct:" + routeId;
        String urlPath = basePath + "/{key}/delegate/{to}";
        if (mountRest) {
            restEndpoint(builder, "POST", urlPath).to(direct);
        }
        RouteDefinition definition = new RouteDefinition("tesseraql/v1", routeId, "route",
                "command-json", java.util.Map.of(), null, def.security(), null, null, null, null,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), null,
                null, null, null, null, null, workflowResponse(), null, null, null, null,
                null);
        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, routeId, "POST", urlPath, definition);
        route.process(new RequestBinder(definition, pathParams(urlPath), compiledAppHome))
                .process(new io.tesseraql.compiler.binding.WorkflowDelegateProcessor(def.id(),
                        def.document().type(), DEFAULT_DATASOURCE))
                .process(responseRenderer(definition));
    }

    /** The {@code within} deadline (ms) declared for a state, or {@code null} when it has none. */
    private static Long deadlineMillis(io.tesseraql.yaml.model.WorkflowDefinition def,
            String state) {
        for (io.tesseraql.yaml.model.DeadlineSpec deadline : def.deadlines()) {
            if (state != null && state.equals(deadline.state()) && deadline.within() != null
                    && !deadline.within().isBlank()) {
                return io.tesseraql.core.util.Durations.toMillis(deadline.within());
            }
        }
        return null;
    }

    /**
     * Parses a transition's assignee-resolution SQL (a {@code SELECT} returning
     * {@code assignee}/{@code candidate_group} rows), dialect-resolved, or {@code null} when the
     * transition assigns no task (roadmap Phase 28 slice 2).
     */
    private java.util.List<io.tesseraql.core.sql.SqlNode> compileAssign(
            io.tesseraql.yaml.manifest.WorkflowFile workflowFile,
            io.tesseraql.yaml.model.TransitionSpec transition) {
        if (transition.assign() == null || transition.assign().file() == null) {
            return null;
        }
        Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(workflowFile.source()
                .getParent().resolve(transition.assign().file()).normalize(), datasourceDialect());
        try {
            return io.tesseraql.core.sql.Sql2WayParser.parse(java.nio.file.Files.readString(file));
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /** A synthesized transition's response: 200 with a small confirmation body. */
    private static io.tesseraql.yaml.model.ResponseSpec workflowResponse() {
        return new io.tesseraql.yaml.model.ResponseSpec(
                new io.tesseraql.yaml.model.ResponseSpec.JsonResponse(200,
                        java.util.Map.of("ok", Boolean.TRUE), null, null, null),
                null, null, null, null, null, null);
    }

    /** The command's binds: the document key (always) plus the transition's declared params. */
    private static java.util.Map<String, String> commandParams(
            io.tesseraql.yaml.model.TransitionSpec transition) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("key", "path.key");
        params.putAll(transition.params());
        return params;
    }

    private boolean workflowManaged(io.tesseraql.yaml.model.WorkflowDefinition def) {
        String mode = def.mode();
        if (mode == null || mode.isBlank()) {
            mode = config.getString("tesseraql.workflow.mode").orElse("app");
        }
        return "managed".equalsIgnoreCase(mode);
    }

    private static String workflowBasePath(io.tesseraql.yaml.model.WorkflowDefinition def) {
        String basePath = def.http() == null ? null : def.http().basePath();
        if (basePath == null || basePath.isBlank()) {
            basePath = "/" + def.id();
        }
        return basePath.startsWith("/") ? basePath : "/" + basePath;
    }

    /**
     * Builds the inbound webhook recipe (roadmap Phase 26): an HMAC-verified, replay-protected POST
     * endpoint that runs the route's SQL pipeline once a signed delivery is authenticated. The
     * verification runs before request binding, so an invalid signature, a stale timestamp, or a
     * replay is rejected before a single row is written. The named verifier must be configured —
     * a webhook with no verifier would be unauthenticated, so an unknown provider fails the build.
     */
    private void buildWebhook(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        if (definition.webhook() == null || definition.webhook().provider() == null
                || definition.webhook().provider().isBlank()) {
            throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': webhook recipe needs a webhook.provider");
        }
        io.tesseraql.yaml.webhook.WebhookVerifiers.Verifier verifier = webhookVerifiers()
                .require(definition.webhook().provider());
        buildTransactionalCommand(builder, routeFile,
                new io.tesseraql.compiler.binding.WebhookVerifyProcessor(definition.id(),
                        verifier));
    }

    private io.tesseraql.yaml.webhook.WebhookVerifiers webhookVerifiers() {
        if (webhookVerifiers == null) {
            webhookVerifiers = io.tesseraql.yaml.webhook.WebhookVerifiers.load(config);
        }
        return webhookVerifiers;
    }

    /**
     * Builds the {@code queue-consume} recipe (roadmap Phase 27) as a {@code direct:queue.<id>}
     * route, never mounted on HTTP: the runtime's messaging consumer claims a message off the
     * channel and sends it here. The route binds the message body, deduplicates by idempotency key
     * (a redelivery short-circuits before a row is written), then runs the SQL pipeline in one
     * transaction — exactly the command-json pipeline, so a consumer is governed like a command.
     * At-least-once delivery comes from the durable channel and the consumer's claim/ack, not this
     * route.
     */
    private void buildQueueConsume(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ConsumeSpec consume = definition.consume();
        if (consume == null || consume.channel() == null || consume.channel().isBlank()
                || consume.topic() == null || consume.topic().isBlank()) {
            throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': queue-consume recipe needs a consume.channel and consume.topic");
        }
        Path routeDir = routeFile.source().getParent();
        // The projection pattern (docs/multi-datasource.md): the consumer's apply transaction may
        // run on a named connector, while the channel, its claim, and the dedup records stay on
        // main - only where the SQL commits moves.
        String datasource = definition.effectiveDatasource();
        requirePlainSqlOffMain(definition);
        String dialect = datasourceDialect(datasource);
        java.util.function.Function<String, Path> stepFile = file -> io.tesseraql.core.dialect.DialectSqlResolver
                .resolve(routeDir.resolve(file).normalize(), dialect);

        String routeId = "queue." + definition.id();
        String direct = "direct:" + routeId;
        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, definition.id(), "QUEUE", "/" + definition.id(),
                definition);
        route.process(new RequestBinder(definition, java.util.List.of(), compiledAppHome));
        route.process(new io.tesseraql.compiler.binding.QueueDedupProcessor(
                consume.channel(), consume.topic(), consume.idempotencyKey()));
        // A deduplicated redelivery stops here, before the pipeline writes a row; the consumer
        // still acknowledges it (the dedup record already records the business key as consumed).
        route.choice()
                .when((org.apache.camel.Predicate) exchange -> Boolean.TRUE
                        .equals(exchange.getProperty(TesseraqlProperties.QUEUE_DUPLICATE)))
                .stop()
                .end();
        route.process(new io.tesseraql.compiler.binding.TransactionalCommandProcessor(
                routeId, definition.sql(), definition.steps(), definition.validate(),
                definition.decide(), definition.notifications(), stepFile, datasource, dialect,
                definition.outbox(), definition.publish(), definition.errors(), appName,
                commandBounds()));
    }

    /**
     * query-export (design ch. 28.10): a synchronous file download streaming the route's query
     * through the same codec/column-mapping machinery as {@code file-export}. The optional
     * {@code export:} block declares format, columns, filename, and locale/timezone; the
     * extraction query stays in the route's {@code sql:} block, and follow-up statements
     * ({@code after:}) need the asynchronous {@code file-export} recipe.
     */
    private void buildQueryExport(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        String routeId = definition.id();
        Path routeDir = routeFile.source().getParent();
        if (spec != null && (spec.sql() != null || spec.after() != null)) {
            throw new TqlException(INVALID_EXPORT, "Route '" + routeId + "': query-export reads"
                    + " its query from the route's sql: block and has no after: hook - use the"
                    + " file-export recipe for asynchronous extraction with follow-up statements");
        }
        String format = spec != null && spec.format() != null ? spec.format() : "csv";
        io.tesseraql.core.files.FileCodec codec = io.tesseraql.core.files.FileCodecs.discover()
                .require(format);
        Path template = spec == null || spec.template() == null
                ? null
                : routeDir.resolve(spec.template()).normalize();
        io.tesseraql.core.files.FileWriteSpec writeSpec = spec == null
                ? new io.tesseraql.core.files.FileWriteSpec(java.util.List.of(), null, null, null,
                        appHome, null, null)
                : spec.toWriteSpec(template, appHome);

        String direct = "direct:" + routeId;
        if (mountRest) {
            restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);
        }
        Path sqlPath = routeDir.resolve(definition.sql().file()).normalize();
        // The export URI is hand-built because its mode and filename are not a binding's, but it
        // carries the same execution parameters every other endpoint does. Omitting them meant a
        // dialect variant was never picked up, the statement ran with no timeout, and on
        // PostgreSQL the default streaming profile left autocommit on - so the driver ignored
        // the fetch size and buffered the whole result set, which is exactly what streaming an
        // export exists to avoid.
        String exportDatasource = bindingDatasource(definition.sql(),
                definition.effectiveDatasource());
        String sqlUri = "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + exportDatasource
                + "&mode=query-export&filename=" + exportFilename(definition, codec)
                + executionParams(exportDatasource, definition.sql());

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, routeFile);
        route.process(
                new RequestBinder(definition, pathParams(routeFile.urlPath()), compiledAppHome))
                .process(new io.tesseraql.compiler.binding.QueryExportBinder(codec, writeSpec,
                        formatDeclaration(spec == null ? null : spec.locale(),
                                "tesseraql.files.locale"),
                        formatDeclaration(spec == null ? null : spec.timezone(),
                                "tesseraql.files.timezone")))
                .to(sqlUri);
    }

    /**
     * file-import (design ch. 28): POST of the raw file body starts an asynchronous import
     * applying the per-row statement; GET {path}/{transferId} reports its state.
     */
    private void buildFileImport(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ImportSpec spec = definition.fileImport();
        String routeId = definition.id();
        Path rowSql = routeFile.source().getParent().resolve(spec.sql().file()).normalize();

        String direct = "direct:" + routeId;
        if (mountRest) {
            restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);
        }
        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, routeFile);
        route.process(new io.tesseraql.compiler.binding.FileImportProcessor(
                routeId, routeFile.urlPath(), appName, spec.format(),
                spec.toReadSpec(), formatDeclaration(spec.locale(), "tesseraql.files.locale"),
                rowSql, spec.effectiveOnError()));
        mountTransferStatus(builder, routeFile, routeId);
    }

    /**
     * file-export (design ch. 28): the start request launches an asynchronous extraction into a
     * generated file; GET {path}/{transferId} reports its state and GET {path}/{transferId}/file
     * streams the result (triggering a download-timed follow-up statement on first fetch).
     */
    private void buildFileExport(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        String routeId = definition.id();
        Path routeDir = routeFile.source().getParent();
        Path querySql = routeDir.resolve(spec.sql().file()).normalize();
        String afterTiming = spec.after() == null ? null : spec.after().effectiveTiming();
        Path afterSql = spec.after() == null
                ? null
                : routeDir.resolve(spec.after().sql().file()).normalize();
        Path template = spec.template() == null
                ? null
                : routeDir.resolve(spec.template()).normalize();

        String direct = "direct:" + routeId;
        if (mountRest) {
            restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);
        }
        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, routeFile);
        route.process(
                new RequestBinder(definition, pathParams(routeFile.urlPath()), compiledAppHome))
                .process(new io.tesseraql.compiler.binding.FileExportStartProcessor(
                        routeId, routeFile.urlPath(), appName, spec.format(),
                        spec.toWriteSpec(template, appHome),
                        formatDeclaration(spec.locale(), "tesseraql.files.locale"),
                        formatDeclaration(spec.timezone(), "tesseraql.files.timezone"),
                        spec.filename(), querySql, afterTiming, afterSql));
        mountTransferStatus(builder, routeFile, routeId);

        String fileDirect = "direct:" + routeId + ".file";
        if (mountRest) {
            restEndpoint(builder, "GET", routeFile.urlPath() + "/{transferId}/file")
                    .to(fileDirect);
        }
        ProcessorDefinition<?> fileRoute = builder.from(fileDirect).routeId(routeId + ".file");
        applySecurity(fileRoute, definition.security(), "GET");
        fileRoute.process(new io.tesseraql.compiler.binding.FileDownloadProcessor());
    }

    /** The route's locale/timezone declaration, falling back to the app-wide configuration. */
    private String formatDeclaration(String declared, String configKey) {
        return declared != null && !declared.isBlank()
                ? declared
                : config.getString(configKey).orElse(null);
    }

    /** GET {path}/{transferId}: the shared status endpoint, secured like its parent route. */
    private void mountTransferStatus(RouteBuilder builder, RouteFile routeFile, String routeId) {
        String direct = "direct:" + routeId + ".status";
        if (mountRest) {
            restEndpoint(builder, "GET", routeFile.urlPath() + "/{transferId}").to(direct);
        }
        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId + ".status");
        applySecurity(route, routeFile.definition().security(), "GET");
        route.process(new io.tesseraql.compiler.binding.FileTransferStatusProcessor(
                routeFile.urlPath()));
    }

    private static String exportFilename(RouteDefinition definition,
            io.tesseraql.core.files.FileCodec codec) {
        if (definition.fileExport() != null && definition.fileExport().filename() != null) {
            return definition.fileExport().filename();
        }
        if (definition.response() != null && definition.response().stream() != null
                && definition.response().stream().filename() != null) {
            return definition.response().stream().filename();
        }
        return definition.id() + codec.extension();
    }

    /**
     * Builds a template-rendered route: {@code query-html} (SQL/contract/service data into an HTML
     * page or fragment) and {@code page} (the same pipeline, typically without a data binding, for
     * forms and static pages, design ch. 6.4). When {@code response.file} is declared the template
     * renders as a text file response (e.g. a generated config download) instead of HTML.
     */
    private void buildTemplatePage(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        Path routeDir = routeFile.source().getParent();
        ProcessorDefinition<?> route = pipelineThroughSql(builder, routeFile);
        if (routeFile.definition().response().file() != null) {
            route.process(new io.tesseraql.compiler.binding.FileResponseRenderer(
                    routeFile.definition().response().file(), appHome, routeDir));
        } else {
            var html = routeFile.definition().response().html();
            // A declarative view (roadmap Phase 39): compile the response.html.view reference —
            // parse + validate the document and derive a form's fields from its action route's
            // input: block — so a bad view fails the build, not the request.
            io.tesseraql.compiler.binding.ViewBinding viewBinding = html != null
                    && html.view() != null
                            ? io.tesseraql.compiler.binding.ViewBinding.of(appHome, routeDir,
                                    html.view(), routeFile.definition(), this::postRouteByPath)
                            : null;
            route.process(new HtmlResponseRenderer(withDefaultHeaders(html), appHome,
                    routeDir, i18n.defaultTag(), viewBinding));
        }
        applyHttpCache(route, routeFile.definition());
        // pipelineThroughSql opened the idempotency record; closing it here is what stops a
        // retry with the same key answering 409 for the whole TTL instead of serving the page.
        applyIdempotencyComplete(route, routeFile.definition());
    }

    /** The POST route serving a path — a form view's {@code action:} target. */
    private RouteDefinition postRouteByPath(String path) {
        for (RouteFile candidate : manifest.routes()) {
            if ("POST".equalsIgnoreCase(candidate.httpMethod())
                    && candidate.urlPath().equals(path)) {
                return candidate.definition();
            }
        }
        return null;
    }

    /** Builds the common route head: REST endpoint, security, request binding, SQL execution. */
    private ProcessorDefinition<?> pipelineThroughSql(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;

        if (mountRest) {
            restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);
        }

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, routeFile);
        applyIdempotencyBegin(route, definition);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, pathParams(routeFile.urlPath()),
                        compiledAppHome));
        // Declarative pagination (roadmap Phase 41): compute the page window before the main
        // query executes; the producer appends the dialect clause and publishes `page`.
        if (definition.pagination() != null) {
            step = step
                    .process(new io.tesseraql.compiler.binding.PageBinder(definition.pagination()));
        }
        // A route may have no data binding at all (the page recipe: forms, static pages).
        if (definition.sql() != null) {
            step = step.to(executionUri(routeFile, definition.sql(), "sql"));
        }
        // Additional named queries run in authored order, each result keyed under its name.
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(routeFile, entry.getValue(), entry.getKey()));
        }
        // http: sources run after the SQL, each keyed under its name like a named query
        // (docs/connectors.md, "HTTP sources") — the response composes them, never the SQL.
        for (var entry : definition.http().entrySet()) {
            step = step.process(new io.tesseraql.compiler.binding.HttpSourceProcessor(
                    entry.getKey(), entry.getValue()));
        }
        if (definition.pagination() != null) {
            step = step.process(new io.tesseraql.compiler.binding.PageHeaders());
        }
        return step;
    }

    /**
     * Builds an application-declared MCP tool (roadmap Phase 24 follow-on) as a {@code direct:}
     * route, never mounted on HTTP. The route is the same pipeline a {@code query-json} /
     * {@code command-json} route runs - telemetry, the tool's own security (auth + policy), input
     * binding and validation, SQL or the transactional command - so a tool is governed exactly like
     * a route. The runtime's MCP endpoint sends to {@code direct:mcp.<id>} and reads the JSON result.
     */
    private void buildMcpTool(RouteBuilder builder, ToolFile toolFile) {
        RouteDefinition definition = toolFile.definition();
        Path toolDir = toolFile.source().getParent();
        String routeId = "mcp." + definition.id();
        String direct = "direct:" + routeId;

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyCommonGovernance(route, definition.id(), "MCP", "/" + definition.id(), definition);
        applyIdempotencyBegin(route, definition);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, java.util.List.of(), compiledAppHome));

        if (usesTransactionalCommand(definition)) {
            String datasource = definition.effectiveDatasource();
            requirePlainSqlOffMain(definition);
            String dialect = datasourceDialect(datasource);
            java.util.function.Function<String, Path> stepFile = file -> io.tesseraql.core.dialect.DialectSqlResolver
                    .resolve(toolDir.resolve(file).normalize(), dialect);
            step = step.process(new io.tesseraql.compiler.binding.TransactionalCommandProcessor(
                    routeId, definition.sql(), definition.steps(), definition.validate(),
                    definition.decide(), definition.notifications(), stepFile, datasource,
                    dialect, definition.outbox(), definition.publish(), definition.errors(),
                    appName, commandBounds()));
        } else if (definition.sql() != null) {
            step = step.to(executionUri(toolDir, definition.sql(), "sql",
                    definition.effectiveDatasource()));
        }
        // Same placement as an HTTP command's: after the write, so a rollback bypasses it. A
        // tool that changes data has the same reason to refresh a live view that a route does,
        // and emit: was accepted here while doing nothing at all.
        if (!definition.emit().isEmpty()) {
            step = step.process(new io.tesseraql.compiler.binding.TopicEmitProcessor(
                    definition.emit()));
        }
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(toolDir, entry.getValue(), entry.getKey(),
                            definition.effectiveDatasource()));
        }
        step.process(mcpToolRenderer(definition));
        applyIdempotencyComplete(step, definition);
    }

    /**
     * Builds an application-declared MCP resource (roadmap Phase 24) as a read-only {@code direct:}
     * route, never mounted on HTTP. It runs the same read pipeline a {@code query-json} route runs -
     * telemetry, the resource's own security (auth + policy), tenancy and locale resolution, and the
     * 2-way SQL - so a resource is governed exactly like a read route. The runtime's MCP endpoint
     * sends to {@code direct:mcp.resource.<id>} on {@code resources/read} and returns the JSON body
     * as the resource contents. A resource declares no {@code input:} (it is addressed only by its
     * uri), so the binder runs with no path or request parameters; idempotency does not apply to a
     * read.
     */
    private void buildMcpResource(RouteBuilder builder, ResourceFile resourceFile) {
        RouteDefinition definition = resourceFile.definition();
        Path resourceDir = resourceFile.source().getParent();
        String routeId = "mcp.resource." + definition.id();
        String direct = "direct:" + routeId;

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                definition.id(), "MCP-RESOURCE", "/" + definition.id(), appName));
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security(), "GET");
        applyTenancy(route);
        applyI18n(route);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, java.util.List.of(), compiledAppHome));
        if (definition.sql() != null) {
            step = step.to(executionUri(resourceDir, definition.sql(), "sql",
                    definition.effectiveDatasource()));
        }
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(resourceDir, entry.getValue(), entry.getKey(),
                            definition.effectiveDatasource()));
        }
        step.process(mcpToolRenderer(definition));
    }

    /**
     * Builds an application-declared MCP Apps UI resource (roadmap Phase 24) as a read-only
     * {@code direct:} route, never mounted on HTTP. It runs the same read-and-render pipeline a
     * {@code query-html} route runs - telemetry, the resource's own security, tenancy and locale
     * resolution, the 2-way SQL, then the Thymeleaf template - so the route renders the same
     * {@code hc-*} fragment a page would. The runtime's MCP endpoint sends to
     * {@code direct:mcp.ui.<id>} on {@code resources/read} and returns the rendered HTML as the
     * resource contents. A UI resource declares no {@code input:} (it is addressed only by its
     * {@code ui://} uri), so the binder runs with no parameters.
     */
    private void buildMcpUi(RouteBuilder builder, Path appHome, UiResourceFile uiFile) {
        RouteDefinition definition = uiFile.definition();
        Path uiDir = uiFile.source().getParent();
        String routeId = "mcp.ui." + definition.id();
        String direct = "direct:" + routeId;

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                definition.id(), "MCP-UI", "/" + definition.id(), appName));
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security(), "GET");
        applyTenancy(route);
        applyI18n(route);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, java.util.List.of(), compiledAppHome));
        if (definition.sql() != null) {
            step = step.to(executionUri(uiDir, definition.sql(), "sql",
                    definition.effectiveDatasource()));
        }
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(uiDir, entry.getValue(), entry.getKey(),
                            definition.effectiveDatasource()));
        }
        step.process(new HtmlResponseRenderer(withDefaultHeaders(definition.response().html()),
                appHome, uiDir, i18n.defaultTag()));
    }

    /** A tool's result renderer: its declared JSON shape, or the raw SQL/command result. */
    private org.apache.camel.Processor mcpToolRenderer(RouteDefinition definition) {
        if (definition.response() != null && definition.response().json() != null) {
            return new JsonResponseRenderer(definition.response().json());
        }
        return new io.tesseraql.compiler.binding.McpToolResultRenderer();
    }

    /** Extracts {@code {name}} path-parameter names from a URL template. */
    private static java.util.List<String> pathParams(String urlPath) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = io.tesseraql.core.sql.SqlIdentifiers.PLACEHOLDER
                .matcher(urlPath);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Builds an execution step URI: a service provider, a tesseraql-iam contract or a SQL file. */
    private String executionUri(RouteFile routeFile, io.tesseraql.yaml.model.SqlBinding binding,
            String resultKey) {
        return executionUri(routeFile.source().getParent(), binding, resultKey,
                routeFile.definition().effectiveDatasource());
    }

    /** As {@link #executionUri(RouteFile, io.tesseraql.yaml.model.SqlBinding, String)}, resolving
     * SQL files relative to {@code sourceDir} (shared by routes and MCP tools). The binding's own
     * {@code datasource:} wins over {@code routeDatasource}, the route-level connector (roadmap
     * Phase 53); the baked dialect follows the connector the SQL actually runs on. */
    private String executionUri(Path sourceDir, io.tesseraql.yaml.model.SqlBinding binding,
            String resultKey, String routeDatasource) {
        if (binding.isService()) {
            return "tesseraql-service:call?name=" + binding.service() + "&resultKey=" + resultKey;
        }
        if (binding.isContract()) {
            return "tesseraql-iam:contract?name=" + binding.contract()
                    + "&mode=" + binding.effectiveMode() + "&resultKey=" + resultKey;
        }
        String datasource = bindingDatasource(binding, routeDatasource);
        Path sqlPath = sourceDir.resolve(binding.file()).normalize();
        return "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + datasource
                + "&mode=" + binding.effectiveMode()
                + "&resultKey=" + resultKey
                + executionParams(datasource, binding);
    }

    /**
     * The execution parameters every {@code tesseraql-sql:} endpoint carries, whatever its mode.
     * {@code dialect} is the load-bearing one: the producer resolves {@code foo.<dialect>.sql}
     * variants from it, picks the dialect's streaming profile, and folds column labels with it —
     * a hand-built URI that omits it silently runs the base file with default streaming.
     */
    private String executionParams(String datasource, io.tesseraql.yaml.model.SqlBinding binding) {
        return "&dialect=" + datasourceDialect(datasource)
                + "&maxRows=" + effectiveMaxRows(binding)
                + "&onOverflow=" + effectiveOnOverflow(binding)
                + "&queryTimeoutSeconds=" + effectiveTimeoutSeconds(binding);
    }

    /** The connector a binding runs on: its own {@code datasource:} when declared, else the route's. */
    private static String bindingDatasource(io.tesseraql.yaml.model.SqlBinding binding,
            String routeDatasource) {
        return binding.datasource() == null || binding.datasource().isBlank()
                ? routeDatasource
                : binding.datasource();
    }

    /**
     * The statement timeout for a binding (roadmap Phase 45): the per-binding override wins,
     * else the app-wide {@code tesseraql.sql.timeoutSeconds}, else 30 — a runaway query is
     * bounded BY DEFAULT. An explicit {@code 0} disables the guard for a deliberately
     * long-running statement.
     */
    private int effectiveTimeoutSeconds(io.tesseraql.yaml.model.SqlBinding binding) {
        if (binding.timeoutSeconds() != null) {
            return Math.max(0, binding.timeoutSeconds());
        }
        return config.getString("tesseraql.sql.timeoutSeconds")
                .map(Integer::parseInt)
                .map(value -> Math.max(0, value))
                .orElse(30);
    }

    /**
     * A command transaction on a named connector is plain SQL (docs/multi-datasource.md): the
     * outbox, sequences, and workflow tables live on {@code main}, so {@code notify:}/
     * {@code publish:}/{@code outbox:} and sequence allocation cannot join a non-main
     * transaction. Lint reports this at build time ({@code TQL-YAML-1036}); this guard keeps a
     * hot-reloaded or hand-mounted route honest at compile time.
     */
    private void requirePlainSqlOffMain(RouteDefinition definition) {
        if (DEFAULT_DATASOURCE.equals(definition.effectiveDatasource())) {
            return;
        }
        boolean anchored = !definition.notifications().isEmpty() || definition.publish() != null
                || definition.outbox() != null
                || (definition.sql() != null && definition.sql().isSequence())
                || definition.steps().values().stream()
                        .anyMatch(io.tesseraql.yaml.model.SqlBinding::isSequence);
        if (anchored) {
            throw new TqlException(MAIN_ANCHORED, "Route '" + definition.id()
                    + "': notify:/publish:/outbox: and sequence allocation ride the main"
                    + " connector and cannot join a 'datasource: "
                    + definition.effectiveDatasource() + "' transaction");
        }
    }

    /** Resolves the main datasource's dialect, inferring it from the JDBC URL when unset. */
    private String datasourceDialect() {
        return datasourceDialect(DEFAULT_DATASOURCE);
    }

    /**
     * Resolves a named connector's dialect exactly as {@code main}'s: the declared
     * {@code tesseraql.datasources.<name>.dialect}, else inferred from its JDBC URL (roadmap
     * Phase 53) — so a MySQL connector beside a PostgreSQL main paginates with its own clauses.
     */
    private String datasourceDialect(String datasource) {
        String prefix = "tesseraql.datasources." + datasource + ".";
        return config.getString(prefix + "dialect")
                .orElseGet(() -> io.tesseraql.core.dialect.Dialect
                        .fromJdbcUrl(config.getString(prefix + "jdbcUrl").orElse(""))
                        .map(io.tesseraql.core.dialect.Dialect::id)
                        .orElse(""));
    }

    /**
     * The governance steps every served pipeline carries, in the order the rest of the pipeline
     * needs them: telemetry first so a rejection is still measured, audit next, then the
     * admission guards, then authentication, then the tenant and locale the binder reads.
     *
     * <p>This exists because it used to be six hand-written lists. Each {@code build*} method
     * re-stated the sequence, and each dropped a different step — file routes lost tenancy and
     * rate limiting, queue consumers and MCP tools lost the audit trail, workflow delegation
     * lost almost all of it. The recipe governance matrix
     * ({@code RecipeGovernanceTest}) asserts the compiled output against
     * {@link #GOVERNED_STEPS}, so a recipe that skips a step fails the build rather than a
     * review.
     *
     * <p>A recipe that genuinely must skip a step does not call this — it declares the skip at
     * its own call site, where a reviewer sees the reason.
     */
    private void applyCommonGovernance(ProcessorDefinition<?> route, String id, String method,
            String path, RouteDefinition definition) {
        applyTelemetry(route, id, method, path);
        applyAudit(route, id, method, path, definition);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security(), method);
        applyTenancy(route);
        applyI18n(route);
    }

    /**
     * The governance an attachment route can carry. It has no {@code policy:} or {@code input:}
     * of its own, so concurrency, lane and the audit trail have nothing to read — the rest
     * applies, and tenancy in particular, which every business route resolves and all three
     * attachment routes used to skip. i18n was applied to upload only, so a list or download
     * error rendered in the default locale while an upload error localized.
     */
    private void applyAttachmentGovernance(ProcessorDefinition<?> route, String id, String method,
            String path, SecuritySpec security) {
        applyTelemetry(route, id, method, path);
        applySecurity(route, security, method);
        applyTenancy(route);
        applyI18n(route);
    }

    private void applyCommonGovernance(ProcessorDefinition<?> route, RouteFile routeFile) {
        applyCommonGovernance(route, routeFile.definition().id(), routeFile.httpMethod(),
                routeFile.urlPath(), routeFile.definition());
    }

    /**
     * The processors {@link #applyCommonGovernance} contributes, in order — the executable form
     * of Matrix 1 in docs/route-governance-parity.md. {@code Gate} is the admission guard the
     * rate limiters install. Every one of these is conditional on configuration (a route with no
     * {@code policy:} has no gate, an app with tenancy off has no resolution), so the matrix
     * test enables them all before asserting.
     */
    static final java.util.List<String> GOVERNED_STEPS = java.util.List.of(
            "RouteTelemetry", "RouteAudit", "Gate", "LaneGate", "TenantResolution",
            "LocaleResolution");

    /** Inserts the route telemetry step (span + invocation counter) at the route head (ch. 25). */
    private void applyTelemetry(ProcessorDefinition<?> route, RouteFile routeFile) {
        applyTelemetry(route, routeFile.definition().id(), routeFile.httpMethod(),
                routeFile.urlPath());
    }

    private void applyTelemetry(ProcessorDefinition<?> route, String id, String method,
            String path) {
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                id, method, path, appName, accessLogEnabled()));
    }

    /** The opt-in HTTP access log (roadmap Phase 45): one correlated line per request. */
    private boolean accessLogEnabled() {
        return config.getString("tesseraql.logging.accessLog")
                .map(Boolean::parseBoolean).orElse(false);
    }

    /**
     * The opt-in business-route audit trail (roadmap Phase 45): appended only when
     * {@code tesseraql.audit.routes.enabled} is set, so un-audited apps pay nothing.
     */
    private void applyAudit(ProcessorDefinition<?> route, RouteFile routeFile) {
        applyAudit(route, routeFile.definition().id(), routeFile.httpMethod(),
                routeFile.urlPath(), routeFile.definition());
    }

    private void applyAudit(ProcessorDefinition<?> route, String id, String method, String path,
            RouteDefinition definition) {
        if (!config.getString("tesseraql.audit.routes.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            return;
        }
        route.process(new io.tesseraql.compiler.binding.RouteAudit(
                id, method, path, appName, definition.input()));
    }

    /**
     * Dispatches the route onto its declared execution lane (design ch. 24): a backpressure gate
     * followed by a {@code threads()} handoff to the lane's executor, so the remaining steps run on
     * a virtual (or platform) thread.
     */
    private void applyLane(ProcessorDefinition<?> route, RouteDefinition definition) {
        if (definition.admission() == null || definition.admission().lane() == null) {
            return;
        }
        String lane = definition.admission().lane();
        route.process(new io.tesseraql.compiler.binding.LaneGate(lane));
        route.threads().executorService(TesseraqlProperties.laneExecutorRef(lane))
                .callerRunsWhenRejected(false);
    }

    /** Resolves and propagates the request tenant when tenancy is enabled (design ch. 30). */
    private void applyTenancy(ProcessorDefinition<?> route) {
        if (tenancy.enabled()) {
            route.process(new io.tesseraql.compiler.binding.TenantResolution(tenancy));
        }
    }

    /** Resolves the request locale after authentication, before binding (roadmap Phase 22). */
    private void applyI18n(ProcessorDefinition<?> route) {
        route.process(new io.tesseraql.compiler.binding.LocaleResolution(i18n));
    }

    /** Inserts per-route rate limit and concurrency guards when declared (design ch. 36.1). */
    private void applyConcurrency(ProcessorDefinition<?> route, RouteDefinition definition) {
        if (definition.admission() == null) {
            return;
        }
        AdmissionSpec.RateLimit rateLimit = definition.admission().rateLimit();
        if (rateLimit != null && rateLimit.requestsPerSecond() != null) {
            int rps = rateLimit.requestsPerSecond();
            int burst = rateLimit.burst() != null ? rateLimit.burst() : rps;
            if (rateLimit.isCluster()) {
                // One budget across every node sharing the main database, leased from the
                // tql_rate_lease ledger (docs/deployment.md, "Cluster rate limits").
                route.process(new io.tesseraql.compiler.binding.ClusterRateLimiter(
                        appName + "|" + definition.id(), rps, rateLimit.burst()).acquire());
            } else {
                route.process(new RateLimiter(rps, burst).acquire());
            }
        }
        AdmissionSpec.Concurrency concurrency = definition.admission().concurrency();
        if (concurrency != null && concurrency.maxInFlight() != null) {
            route.process(new ConcurrencyLimiter(concurrency.maxInFlight()).acquire());
        }
    }

    /** Inserts the idempotency begin step and a short-circuit for replays (design ch. 39.5). */
    private void applyIdempotencyBegin(ProcessorDefinition<?> route, RouteDefinition definition) {
        IdempotencySpec idempotency = definition.idempotency();
        if (idempotency == null) {
            return;
        }
        String scope = idempotency.scope() != null ? idempotency.scope() : definition.id();
        long ttl = idempotency.ttl() != null
                ? Durations.toMillis(idempotency.ttl())
                : DEFAULT_IDEMPOTENCY_TTL;
        route.process(IdempotencyProcessors.begin(scope, ttl, idempotency.isRequired()));
        route.choice()
                .when((org.apache.camel.Predicate) exchange -> Boolean.TRUE
                        .equals(exchange.getProperty(IdempotencyProcessors.REPLAY_PROPERTY)))
                .stop()
                .end();
    }

    /** Appends the idempotency complete step after the response is rendered. */
    private void applyIdempotencyComplete(ProcessorDefinition<?> route,
            RouteDefinition definition) {
        IdempotencySpec idempotency = definition.idempotency();
        if (idempotency != null) {
            String scope = idempotency.scope() != null ? idempotency.scope() : definition.id();
            route.process(IdempotencyProcessors.complete(scope));
        }
    }

    /**
     * The app-wide execution bounds a command's statements inherit. A command opens its own JDBC
     * transaction with no transaction manager to bound it, so it reads the same config keys the
     * route-level SQL path does rather than running unbounded (docs/route-governance-parity.md).
     */
    private io.tesseraql.compiler.binding.TransactionalCommandProcessor.Bounds commandBounds() {
        int timeout = config.getString("tesseraql.sql.timeoutSeconds")
                .map(Integer::parseInt)
                .map(value -> Math.max(0, value))
                .orElse(30);
        int maxRows = config.getString("tesseraql.resultMaterialization.maxRows")
                .map(Integer::parseInt)
                .orElse(DEFAULT_MAX_ROWS);
        String onOverflow = config.getString("tesseraql.resultMaterialization.onOverflow")
                .orElse("fail");
        return new io.tesseraql.compiler.binding.TransactionalCommandProcessor.Bounds(
                timeout, maxRows, onOverflow);
    }

    /** Resolves the effective row cap: route override, then global config, then default (ch. 28.7). */
    private int effectiveMaxRows(SqlBinding sql) {
        if (sql.materialize() != null && sql.materialize().maxRows() != null) {
            return sql.materialize().maxRows();
        }
        return config.getString("tesseraql.resultMaterialization.maxRows")
                .map(Integer::parseInt)
                .orElse(DEFAULT_MAX_ROWS);
    }

    private String effectiveOnOverflow(SqlBinding sql) {
        if (sql.materialize() != null && sql.materialize().onOverflow() != null) {
            return sql.materialize().onOverflow();
        }
        return config.getString("tesseraql.resultMaterialization.onOverflow").orElse("fail");
    }

    /**
     * The response with the app-wide default response headers merged under its own
     * (docs/route-defaults.md): route entries win by name, and a route entry valued
     * {@code unset} removes the header entirely.
     */
    private io.tesseraql.yaml.model.ResponseSpec.HtmlResponse withDefaultHeaders(
            io.tesseraql.yaml.model.ResponseSpec.HtmlResponse html) {
        if (html == null) {
            return null;
        }
        return html.withHeaders(responseHeaders.mergeUnder(html.headers()));
    }

    /** Inserts authenticate/authorize steps before binding when the route declares security. */
    private void applySecurity(ProcessorDefinition<?> route, SecuritySpec security,
            String httpMethod) {
        if (security == null) {
            return;
        }
        if (security.auth() != null && !"public".equals(security.auth())) {
            route.to("tesseraql-auth:authenticate?auth=" + security.auth());
        }
        if (security.csrfEnforced(httpMethod)) {
            route.to("tesseraql-auth:csrf");
        }
        if (security.policy() != null && !security.policy().isBlank()) {
            route.to("tesseraql-auth:authorize?policy=" + security.policy());
        }
    }

    private RestDefinition restEndpoint(RouteBuilder builder, String method, String path) {
        return switch (method) {
            case "GET" -> builder.rest().get(path);
            case "POST" -> builder.rest().post(path);
            case "PUT" -> builder.rest().put(path);
            case "PATCH" -> builder.rest().patch(path);
            case "DELETE" -> builder.rest().delete(path);
            default ->
                throw new TqlException(UNSUPPORTED_RECIPE, "Unsupported HTTP method: " + method);
        };
    }
}
