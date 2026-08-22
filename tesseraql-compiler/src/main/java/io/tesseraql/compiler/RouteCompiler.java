package io.tesseraql.compiler;

import io.tesseraql.compiler.binding.ConcurrencyLimiter;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.binding.HtmlResponseRenderer;
import io.tesseraql.compiler.binding.IdempotencyProcessors;
import io.tesseraql.compiler.binding.JsonResponseRenderer;
import io.tesseraql.compiler.binding.RateLimiter;
import io.tesseraql.compiler.binding.RequestBinder;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.PipelineBuilder;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.util.Durations;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.auth.AuthStep;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ResourceFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.manifest.UiResourceFile;
import io.tesseraql.yaml.model.AdmissionSpec;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.IdempotencySpec;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SecuritySpec;
import java.nio.file.Path;

/**
 * Compiles a TesseraQL {@link AppManifest} into pipelines (design ch. 7).
 *
 * <p>For each route file the compiler dispatches on the recipe and writes a pipeline into the
 * context's registry — request binder, SQL step, response renderer — and declares the mount the
 * HTTP edge serves it at (docs/camel-removal.md structural decision 1).
 */
public final class RouteCompiler {

    private static final TqlErrorCode UNSUPPORTED_RECIPE = new TqlErrorCode(TqlDomain.ROUTE, 3100);
    /** TQL-ROUTE-3101: a query-export route declares an after: hook, which needs file-export. */
    private static final TqlErrorCode INVALID_EXPORT = new TqlErrorCode(TqlDomain.ROUTE, 3101);
    /** TQL-ROUTE-3112: a non-main command transaction cannot carry main-anchored features. */
    private static final TqlErrorCode MAIN_ANCHORED = new TqlErrorCode(TqlDomain.ROUTE, 3112);
    /** TQL-ROUTE-3116: a prompt-text recipe declares command steps, and prompts/get is a read. */
    private static final TqlErrorCode PROMPT_WRITES = new TqlErrorCode(TqlDomain.ROUTE, 3116);
    /** TQL-ROUTE-3117: a prompt-text recipe declares no response.text: to render its message. */
    private static final TqlErrorCode PROMPT_WITHOUT_TEXT = new TqlErrorCode(TqlDomain.ROUTE, 3117);
    private static final String DEFAULT_DATASOURCE = "main";
    private static final int DEFAULT_MAX_ROWS = 10_000;
    private static final long DEFAULT_IDEMPOTENCY_TTL = java.time.Duration.ofHours(24).toMillis();

    private AppConfig config;
    /**
     * Cached so a prefix is resolved once per compile rather than per route; {@code null} until
     * first use, and the empty string when unset (which is every deployment that has not asked
     * for one).
     */
    private String basePath;
    private io.tesseraql.yaml.config.ResponseHeaderDefaults responseHeaders;
    private AppManifest manifest;
    private java.nio.file.Path compiledAppHome;
    private io.tesseraql.compiler.binding.TenancySettings tenancy;
    private io.tesseraql.yaml.i18n.I18nSettings i18n;
    private io.tesseraql.yaml.webhook.WebhookVerifiers webhookVerifiers;
    private boolean mountRest = true;
    private String appName;
    private ExpressionFunctions functions = ExpressionFunctions.processDefault();

    /**
     * Sets the app name routes are attributed to (e.g. outbox events). One runtime serves one
     * user application; the bundled system apps mounted beside it (Studio, the consoles) share
     * that application's config, so their name cannot come from {@code tesseraql.app.name} and
     * the host sets it explicitly. Unset, the config value applies.
     */
    public RouteCompiler appName(String appName) {
        this.appName = appName;
        return this;
    }

    /**
     * Sets the function set this compile resolves custom calls against — a hosted runtime
     * passes its own; unset, the process default applies.
     */
    public RouteCompiler functions(ExpressionFunctions functions) {
        this.functions = functions;
        return this;
    }

    /**
     * The pipelines this run is building (docs/camel-removal.md structural decision 1).
     *
     * <p>Set when {@code configure()} runs, because that is when a context — and therefore the
     * runtime's registry — is in hand. A compiler instance compiles once.
     */
    private Pipelines.Compilation pipelines;

    /** Compiles every route of {@code manifest} into {@code context}'s pipeline registry. */
    public void compile(RuntimeContext context, AppManifest manifest) {
        compile(context, manifest, true, null);
    }

    /**
     * Compiles the manifest into {@code context}. When {@code mountRest} is false the pipelines
     * are built without their HTTP mounts — used to hot-reload route bodies in place. When {@code onlyRouteIds} is non-null only those route ids are built
     * (design ch. 16.8 live reload).
     */
    public void compile(RuntimeContext context, AppManifest manifest, boolean mountRest,
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
        // Per-route response.onError steering (HX-Retarget/HX-Reswap), resolved at error
        // time from the failing route id; the error renderer is one shared exception handler.
        java.util.Map<String, io.tesseraql.yaml.model.ResponseSpec.OnError> onErrorByRoute = onErrorByRoute(
                manifest);
        // The same header block every successful HTML response carries. The error path
        // short-circuits the render that merged it, so an error page and an htmx error
        // fragment - both HTML the browser renders like any other - used to arrive with
        // no CSP and no X-Frame-Options at all.
        java.util.Map<String, String> errorHeaders = responseHeaders.headers();
        // Every pipeline this run builds inherits both clauses, most specific first.
        // The DSL used to arrange that by side effect — an onException declared in a
        // builder's configure() was copied into each route it went on to create — and
        // here it is an argument, so a reload cannot accumulate a second copy.
        pipelines = Pipelines.of(context).compiling(java.util.List.of(
                new Pipeline.Handler(java.util.List.of(TqlException.class.getName()),
                        new ErrorResponseRenderer(i18n, onErrorByRoute,
                                manifest.appHome(), errorHeaders)),
                new Pipeline.Handler(java.util.List.of(Exception.class.getName()),
                        new ErrorResponseRenderer(i18n, onErrorByRoute,
                                manifest.appHome(), errorHeaders))));
        for (RouteFile routeFile : manifest.routes()) {
            if (onlyRouteIds == null
                    || onlyRouteIds.contains(routeFile.definition().id())) {
                buildRoute(context, manifest.appHome(), routeFile);
            }
        }
        // Application-declared MCP tools (roadmap Phase 24): each compiles to a direct:
        // route consumed by the runtime's MCP endpoint, never mounted on HTTP.
        for (ToolFile toolFile : manifest.tools()) {
            if (onlyRouteIds == null
                    || onlyRouteIds.contains(toolFile.definition().id())) {
                buildMcpTool(context, toolFile);
            }
        }
        // Application-declared MCP resources (roadmap Phase 24): read-only context, served
        // over the same MCP endpoint; each compiles to a read-only direct: route.
        for (ResourceFile resourceFile : manifest.resources()) {
            if (onlyRouteIds == null
                    || onlyRouteIds.contains(resourceFile.definition().id())) {
                buildMcpResource(context, resourceFile);
            }
        }
        // Application-declared MCP Apps UI resources (roadmap Phase 24): each renders an
        // hc-* fragment, served as a ui:// resource over the same MCP endpoint.
        for (UiResourceFile uiFile : manifest.uiResources()) {
            if (onlyRouteIds == null
                    || onlyRouteIds.contains(uiFile.definition().id())) {
                buildMcpUi(context, manifest.appHome(), uiFile);
            }
        }
        // Application-declared MCP prompts (docs/prompt-as-recipe.md): each compiles to a
        // read-only direct: route whose response.text: renders the message.
        for (io.tesseraql.yaml.manifest.PromptFile promptFile : manifest.prompts()) {
            if (onlyRouteIds == null
                    || onlyRouteIds.contains(promptFile.definition().id())) {
                buildMcpPrompt(context, promptFile);
            }
        }
        // Messaging consumers (roadmap Phase 27): each queue-consume route compiles to a
        // direct:queue.<id> route the runtime's channel consumer drives, never mounted on
        // HTTP — so they live outside the REST surface, like MCP tools.
        for (RouteFile consumerFile : manifest.consumers()) {
            if (onlyRouteIds == null
                    || onlyRouteIds.contains(consumerFile.definition().id())) {
                buildQueueConsume(context, consumerFile);
            }
        }
        // Approval workflows (roadmap Phase 28): each workflow synthesizes one
        // transactional-command route per transition, mounted on HTTP — the author declares
        // states and transitions, not a route per transition.
        for (io.tesseraql.yaml.manifest.WorkflowFile workflowFile : manifest.workflows()) {
            buildWorkflow(context, workflowFile, onlyRouteIds);
        }
        // Attachments (roadmap Phase 30): each attachment document synthesizes an off-heap
        // upload route, a list route, and a download route, mounted on HTTP under its
        // basePath — the author declares the owning record and limits, not a route apiece.
        for (io.tesseraql.yaml.manifest.AttachmentFile attachmentFile : manifest
                .attachments()) {
            buildAttachment(context, attachmentFile, onlyRouteIds);
        }
    }

    /**
     * Builds an attachment document's three routes (roadmap Phase 30 slice 1): an off-heap multipart
     * upload {@code POST basePath}, a metadata list {@code GET basePath}, and a download
     * {@code GET basePath/{attachmentId}}. Each carries the document's {@code security:}; the owning
     * record key in {@code basePath} scopes list and download to that record.
     */
    private void buildAttachment(RuntimeContext context,
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
            String served = uploadId;
            if (mountRest) {
                mount(context, "POST", basePath, served);
            }
            PipelineBuilder route = pipelines.pipeline(uploadId);
            applyAttachmentGovernance(route, uploadId, "POST", basePath, security);
            route.process(new io.tesseraql.compiler.binding.AttachmentUploadProcessor(
                    entity, recordKey, def.bucket(), maxBytes, contentTypes));
        }

        String listId = def.id() + ".list";
        if (onlyRouteIds == null || onlyRouteIds.contains(listId)) {
            String served = listId;
            if (mountRest) {
                mount(context, "GET", basePath, served);
            }
            PipelineBuilder route = pipelines.pipeline(listId);
            applyAttachmentGovernance(route, listId, "GET", basePath, security);
            route.process(new io.tesseraql.compiler.binding.AttachmentListProcessor(entity,
                    recordKey));
        }

        String downloadId = def.id() + ".download";
        if (onlyRouteIds == null || onlyRouteIds.contains(downloadId)) {
            String urlPath = basePath + "/{" + idParam + "}";
            String served = downloadId;
            if (mountRest) {
                mount(context, "GET", urlPath, served);
            }
            PipelineBuilder route = pipelines.pipeline(downloadId);
            applyAttachmentGovernance(route, downloadId, "GET", urlPath, security);
            route.process(new io.tesseraql.compiler.binding.AttachmentDownloadProcessor(entity,
                    recordKey, idParam));
        }
    }

    private void buildRoute(RuntimeContext context, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        switch (definition.recipe()) {
            case "query-json", "command-json" -> buildJson(context, routeFile);
            case "query-html", "page" -> buildTemplatePage(context, appHome, routeFile);
            case "query-export" -> buildQueryExport(context, appHome, routeFile);
            case "file-import" -> buildFileImport(context, routeFile);
            case "file-export" -> buildFileExport(context, appHome, routeFile);
            case "webhook" -> buildWebhook(context, routeFile);
            // queue-consume routes live under consume/, compiled from manifest.consumers(), not here.
            // Every designed recipe is implemented, so an unknown one is a typo: fail fast
            // instead of silently dropping the route from the served surface (design ch. 20.14).
            default -> throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': unknown recipe '" + definition.recipe() + "'");
        }
    }

    private void buildJson(RuntimeContext context, RouteFile routeFile) {
        if (usesTransactionalCommand(routeFile.definition())) {
            buildTransactionalCommand(context, routeFile);
            return;
        }
        PipelineBuilder route = applySessionRotation(
                pipelineThroughSql(context, routeFile), routeFile.definition())
                .process(responseRenderer(routeFile.definition()));
        applyHttpCache(route, routeFile.definition());
        applyIdempotencyComplete(route, routeFile.definition());
    }

    /** Declarative HTTP caching for query responses (docs/response-shaping.md). */
    private void applyHttpCache(PipelineBuilder route, RouteDefinition definition) {
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
                || !definition.validate().isEmpty()
                || !definition.notifications().isEmpty()
                // publish: is a transactional-outbox write exactly like notify: — it was missing
                // from this list, so a command with publish: and no transactional step compiled
                // down the read path and the publish was silently dropped.
                || definition.publish() != null
                // A step is transactional when it writes on the command's own connection: SQL
                // or a managed sequence. A contract/service step runs through its component,
                // which has no place in a JDBC transaction, so it keeps the standard pipeline.
                || definition.steps().values().stream()
                        .anyMatch(step -> step.file() != null || step.isSequence());
    }

    /**
     * The declared in-place session rotation (docs/session-rotation.md), appended after
     * successful execution and before the response renders — an execution error diverts
     * to the error renderer first, so a failed command never half-rotates. Session
     * mechanics stay in the auth component beside authenticate/authorize.
     */
    private PipelineBuilder applySessionRotation(PipelineBuilder route,
            RouteDefinition definition) {
        if (definition.response() != null && definition.response().session() != null
                && definition.response().session().rotates()) {
            return route.process(new AuthStep("rotate"));
        }
        return route;
    }

    /** The terminal renderer: a redirect when declared, otherwise the JSON response. */
    private io.tesseraql.pipeline.Step responseRenderer(RouteDefinition definition) {
        if (definition.response() != null && definition.response().redirect() != null) {
            return new io.tesseraql.compiler.binding.RedirectRenderer(
                    definition.response().redirect());
        }
        return new JsonResponseRenderer(withDefaultHeaders(definition.response().json()),
                functions);
    }

    /**
     * Builds a command route through the transactional command processor (design ch. 39.2,
     * roadmap Phase 18): its SQL steps, document-sequence allocations, and outbox event commit
     * atomically in one transaction. Dialect-specific SQL variants resolve per step, like the
     * standard execution pipeline.
     */
    private void buildTransactionalCommand(RuntimeContext context, RouteFile routeFile) {
        buildTransactionalCommand(context, routeFile, null, null);
    }

    private void buildTransactionalCommand(RuntimeContext context, RouteFile routeFile,
            io.tesseraql.pipeline.Step preCommand) {
        buildTransactionalCommand(context, routeFile, preCommand, null);
    }

    /**
     * Builds the transactional command pipeline, optionally inserting {@code preCommand} after the
     * common steps and before request binding — the inbound webhook recipe (roadmap Phase 26) uses
     * it to verify the signed, replay-protected delivery before a single row is written. A
     * {@code workflow} binding (roadmap Phase 28) makes the command a workflow transition: the
     * processor advances the document's state, checks the guard, and appends history in the same
     * transaction.
     */
    private void buildTransactionalCommand(RuntimeContext context, RouteFile routeFile,
            io.tesseraql.pipeline.Step preCommand,
            io.tesseraql.compiler.binding.WorkflowBinding workflow) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String served = routeId;
        if (mountRest) {
            mount(context, routeFile.httpMethod(), routeFile.urlPath(), served);
        }

        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, routeFile);
        applyIdempotencyBegin(route, definition);
        if (preCommand != null) {
            route.process(preCommand);
        }
        PipelineBuilder step = route
                .process(new RequestBinder(definition, routeFile.urlPath(),
                        compiledAppHome, functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());
        // http: sources run before the command, which is the whole point of allowing them here:
        // the connection is not taken until the fetch is done, so the transaction never waits on
        // a third party (docs/lookups.md, decision 19).
        step = httpSourcesFirst(step, definition);
        step = step.process(commandProcessor(routeFile, workflow));
        // Live-view topics broadcast only after a successful commit: an exception in the
        // command processor (rollback) bypasses this step (docs/realtime.md). The catalog
        // invalidation rides the same placement for the same reason — a rollback must not
        // send every reader to reload names that never changed.
        if (!definition.emit().isEmpty()) {
            step = step.process(new io.tesseraql.compiler.binding.TopicEmitProcessor(
                    definition.emit()));
        }
        if (!definition.invalidates().isEmpty()) {
            step = step.process(new io.tesseraql.compiler.binding.CatalogInvalidateProcessor(
                    definition.invalidates()));
        }
        // Named queries still run after the command (outside its transaction), in authored order.
        // http: sources are skipped here — they already ran before the command, and mounting
        // them again would call the partner a second time after the commit, where a flake turns
        // a committed write into an error response.
        for (var entry : definition.sources().entrySet()) {
            if (entry.getValue().isHttp()) {
                continue;
            }
            step = source(step, routeFile, entry.getKey(), entry.getValue());
        }
        applySessionRotation(step, definition).process(responseRenderer(definition));
        applyIdempotencyComplete(step, definition);
    }

    /**
     * A document's {@code http:} sources, ordered before the work that holds a connection. A
     * command fetches before its transaction opens (docs/lookups.md, decision 19) and an export
     * fetches before its extraction (docs/export-pipeline.md, decision 2), for the same reason:
     * a network call inside the window where a connection, a transaction or a cursor is held
     * pins them for however long the partner takes, and a failure here fails the request before
     * a row is written or streamed. The mounting site owes the matching guard: whichever loop
     * mounts the remaining sources afterwards must skip the {@code http:} entries, or each
     * partner is called twice per request.
     */
    private static PipelineBuilder httpSourcesFirst(PipelineBuilder step,
            RouteDefinition definition) {
        PipelineBuilder fetched = step;
        for (var entry : definition.sources().entrySet()) {
            if (entry.getValue().isHttp()) {
                fetched = fetched.process(new io.tesseraql.compiler.binding.HttpSourceProcessor(
                        entry.getKey(), entry.getValue().http()));
            }
        }
        return fetched;
    }

    /**
     * The transactional command processor a route (or a dispatch attempt — the selector
     * invokes members' processors directly, docs/transition-engine.md track B) runs: one
     * construction path, so a member fired through a dispatch is the same pipeline its own
     * REST endpoint runs.
     */
    private io.tesseraql.compiler.binding.TransactionalCommandProcessor commandProcessor(
            RouteFile routeFile, io.tesseraql.compiler.binding.WorkflowBinding workflow) {
        return commandProcessor(routeFile.definition().id(), routeFile.definition(),
                routeFile.source().getParent(), workflow);
    }

    /**
     * The same construction for a document that carries its own route id and source directory:
     * a queue consumer ({@code queue.<id>}) and an MCP tool ({@code mcp.<id>}) run the command
     * pipeline under a synthesized id, off their own declaring directory.
     *
     * @param routeId    the id the processor reports and its outbox events carry
     * @param definition the command document
     * @param sourceDir  the directory the document's step and rule files resolve against
     * @param workflow   the workflow binding for a synthesized transition route, or null
     */
    private io.tesseraql.compiler.binding.TransactionalCommandProcessor commandProcessor(
            String routeId, RouteDefinition definition, Path sourceDir,
            io.tesseraql.compiler.binding.WorkflowBinding workflow) {
        String datasource = definition.effectiveDatasource();
        requirePlainSqlOffMain(definition);
        String dialect = datasourceDialect(datasource);
        java.util.function.Function<String, Path> stepFile = file -> io.tesseraql.core.dialect.DialectSqlResolver
                .resolve(sourceDir.resolve(file).normalize(), dialect);
        return new io.tesseraql.compiler.binding.TransactionalCommandProcessor(routeId,
                io.tesseraql.compiler.binding.CommandDeclaration.of(definition), stepFile,
                datasource, dialect, appName, workflow, commandBounds(), functions);
    }

    /**
     * Builds an approval workflow (roadmap Phase 28): one transactional-command route per
     * transition. State lives in the managed {@code tql_workflow_instance} table or, in app mode, in
     * a column on the business table — selected per workflow, defaulting to the app-wide
     * {@code tesseraql.workflow.mode}.
     */
    private void buildWorkflow(RuntimeContext context,
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
            buildTransactionalCommand(context,
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
            RouteDefinition definition = RouteDefinition.synthesizedCommand(routeId, security,
                    null, java.util.Map.of(), dispatchResponse());
            String served = routeId;
            if (mountRest) {
                mount(context, "POST", urlPath, served);
            }
            String dialect = datasourceDialect(DEFAULT_DATASOURCE);
            PipelineBuilder route = pipelines.pipeline(routeId);
            applyCommonGovernance(route, routeId, "POST", urlPath, definition);
            route.process(new RequestBinder(definition, urlPath, compiledAppHome,
                    functions))
                    .process(new io.tesseraql.compiler.binding.CatalogBinder())
                    .process(new io.tesseraql.compiler.binding.WorkflowDispatchProcessor(
                            def.id(), dispatch.id(), members,
                            io.tesseraql.yaml.decision.DecisionSets.compileUses(
                                    dispatch.decide(), dialect, functions),
                            def.document().table(), def.document().key(), dialect,
                            DEFAULT_DATASOURCE, commandBounds() == null
                                    ? 0
                                    : commandBounds().timeoutSeconds()))
                    .process(responseRenderer(definition));
        }
        buildWorkflowDelegate(context, def, basePath, onlyRouteIds);
    }

    /** The synthesized route file a transition compiles to (roadmap Phase 28). */
    private RouteFile transitionRouteFile(io.tesseraql.yaml.manifest.WorkflowFile workflowFile,
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.yaml.model.TransitionSpec transition, String basePath) {
        io.tesseraql.yaml.model.Binding command = transition.commandFile() == null
                ? null
                : io.tesseraql.yaml.model.Binding.sql(transition.commandFile(), "update",
                        commandParams(transition));
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
                        datasourceDialect(DEFAULT_DATASOURCE), workflowFile.source().getParent(),
                        functions),
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
                        null, null),
                null, null, null, null, null, null, null);
    }

    /** The command-json route a workflow transition compiles to (roadmap Phase 28). */
    private RouteDefinition synthesizedTransition(String routeId,
            io.tesseraql.yaml.model.SecuritySpec security,
            io.tesseraql.yaml.model.Binding command,
            io.tesseraql.yaml.model.TransitionSpec transition) {
        return RouteDefinition.synthesizedCommand(routeId, security, command,
                transition.decide(), workflowResponse());
    }

    /** The compiled task-assignment reminder (Phase 20 channels), or {@code null} when undeclared. */
    private io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify assignNotify(
            io.tesseraql.yaml.model.WorkflowDefinition def) {
        if (def.reminders() == null || def.reminders().assigned() == null) {
            return null;
        }
        return io.tesseraql.yaml.notify.NotifyEvents.compile(def.id(), "assigned",
                def.reminders().assigned(), functions);
    }

    /**
     * Builds the built-in delegation endpoint for a workflow that uses tasks (roadmap Phase 28
     * slice 3): {@code POST {basePath}/{key}/delegate/{to}} reassigns the caller's open task to the
     * delegate, who then sees it in their inbox. Only the current assignee may delegate.
     */
    private void buildWorkflowDelegate(RuntimeContext context,
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
        String served = routeId;
        String urlPath = basePath + "/{key}/delegate/{to}";
        if (mountRest) {
            mount(context, "POST", urlPath, served);
        }
        RouteDefinition definition = RouteDefinition.synthesizedCommand(routeId, def.security(),
                null, java.util.Map.of(), workflowResponse());
        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, routeId, "POST", urlPath, definition);
        route.process(new RequestBinder(definition, urlPath, compiledAppHome,
                functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder())
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
        return parseSql(io.tesseraql.core.dialect.DialectSqlResolver.resolve(workflowFile.source()
                .getParent().resolve(transition.assign().file()).normalize(),
                datasourceDialect()));
    }

    /** A synthesized transition's response: 200 with a small confirmation body. */
    private static io.tesseraql.yaml.model.ResponseSpec workflowResponse() {
        return new io.tesseraql.yaml.model.ResponseSpec(
                new io.tesseraql.yaml.model.ResponseSpec.JsonResponse(200,
                        java.util.Map.of("ok", Boolean.TRUE), null, null),
                null, null, null, null, null, null, null);
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
        String basePath = def.basePath();
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
    private void buildWebhook(RuntimeContext context, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        if (definition.webhook() == null || definition.webhook().provider() == null
                || definition.webhook().provider().isBlank()) {
            throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': webhook recipe needs a webhook.provider");
        }
        io.tesseraql.yaml.webhook.WebhookVerifiers.Verifier verifier = webhookVerifiers()
                .require(definition.webhook().provider());
        buildTransactionalCommand(context, routeFile,
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
    private void buildQueueConsume(RuntimeContext context, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ConsumeSpec consume = definition.consume();
        if (consume == null || consume.channel() == null || consume.channel().isBlank()
                || consume.topic() == null || consume.topic().isBlank()) {
            throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': queue-consume recipe needs a consume.channel and consume.topic");
        }
        // The backstop for TQL-YAML-1051: a consumer mounts no sources — nothing runs before its
        // transaction and nothing reads a result after it — so a declared one is refused here
        // rather than compiled to nothing.
        if (!definition.sources().isEmpty()) {
            throw new TqlException(UNSUPPORTED_RECIPE, "Route '" + definition.id()
                    + "': queue-consume mounts no sources: — the pipeline is its steps:");
        }
        String routeId = "queue." + definition.id();
        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, definition.id(), "QUEUE", "/" + definition.id(),
                definition);
        route.process(new RequestBinder(definition, null, compiledAppHome,
                functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());
        route.process(new io.tesseraql.compiler.binding.QueueDedupProcessor(
                consume.channel(), consume.topic(), consume.idempotencyKey()));
        // A deduplicated redelivery stops here, before the pipeline writes a row; the consumer
        // still acknowledges it (the dedup record already records the business key as consumed).
        route.process(exchange -> {
            if (Boolean.TRUE.equals(
                    exchange.getProperty(TesseraqlProperties.QUEUE_DUPLICATE))) {
                exchange.setRouteStop(true);
            }
        });
        // The projection pattern (docs/multi-datasource.md): the consumer's apply transaction may
        // run on a named connector, while the channel, its claim, and the dedup records stay on
        // main - only where the SQL commits moves.
        route.process(commandProcessor(routeId, definition,
                routeFile.source().getParent(), null));
    }

    /**
     * query-export (design ch. 28.10): a synchronous file download streaming the route's query
     * through the same codec/column-mapping machinery as {@code file-export}. The optional
     * {@code export:} block declares format, columns, filename, and locale/timezone; the
     * extraction is {@code sources.main} on either recipe (docs/unified-sources.md decision 7),
     * and follow-up statements ({@code after:}) need the asynchronous {@code file-export}
     * recipe.
     */
    private void buildQueryExport(RuntimeContext context, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        String routeId = definition.id();
        Path routeDir = routeFile.source().getParent();
        if (spec != null && spec.after() != null) {
            throw new TqlException(INVALID_EXPORT, "Route '" + routeId + "': query-export has no"
                    + " after: hook - use the file-export recipe for asynchronous extraction"
                    + " with follow-up statements");
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

        String served = routeId;
        if (mountRest) {
            mount(context, routeFile.httpMethod(), routeFile.urlPath(), served);
        }
        Path sqlPath = routeDir.resolve(definition.main().file()).normalize();
        // The export URI is hand-built because its mode and filename are not a binding's, but it
        // carries the same execution parameters every other endpoint does. Omitting them meant a
        // dialect variant was never picked up, the statement ran with no timeout, and on
        // PostgreSQL the default streaming profile left autocommit on - so the driver ignored
        // the fetch size and buffered the whole result set, which is exactly what streaming an
        // export exists to avoid.
        String exportDatasource = bindingDatasource(definition.main(),
                definition.effectiveDatasource());
        io.tesseraql.pipeline.sql.SqlStep exportSql = new io.tesseraql.pipeline.sql.SqlStep(
                sqlPath.toString(), exportDatasource, "query-export", "main",
                effectiveMaxRows(definition.main()), effectiveTimeoutSeconds(definition.main()),
                effectiveOnOverflow(definition.main()), exportFilename(definition, codec),
                datasourceDialect(exportDatasource));

        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, routeFile);
        PipelineBuilder step = route
                .process(new RequestBinder(definition, routeFile.urlPath(),
                        compiledAppHome, functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder(
                        formatDeclaration(spec == null ? null : spec.locale(),
                                "tesseraql.files.locale")));
        step = httpSourcesFirst(step, definition);
        step.process(new io.tesseraql.compiler.binding.QueryExportBinder(codec, writeSpec,
                formatDeclaration(spec == null ? null : spec.locale(),
                        "tesseraql.files.locale"),
                formatDeclaration(spec == null ? null : spec.timezone(),
                        "tesseraql.files.timezone"),
                declaredExportRowCap(spec, format), exportQueries(definition, routeDir),
                httpSourceNames(definition), enrichProcessors(routeDir, definition)))
                .process(exportSql);
    }

    /**
     * file-import (design ch. 28): POST of the raw file body starts an asynchronous import
     * applying the per-row statement; GET {path}/{transferId} reports its state.
     */
    private void buildFileImport(RuntimeContext context, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ImportSpec spec = definition.fileImport();
        String routeId = definition.id();
        Path rowSql = routeFile.source().getParent()
                .resolve(definition.rowStep().file()).normalize();

        String served = routeId;
        if (mountRest) {
            mount(context, routeFile.httpMethod(), routeFile.urlPath(), served);
        }
        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, routeFile);
        route.process(new io.tesseraql.compiler.binding.FileImportProcessor(
                routeId, routeFile.urlPath(), appName, spec.format(),
                spec.toReadSpec(), formatDeclaration(spec.locale(), "tesseraql.files.locale"),
                rowSql, spec.effectiveOnError()));
        mountTransferStatus(context, routeFile, routeId);
    }

    /**
     * file-export (design ch. 28): the start request launches an asynchronous extraction into a
     * generated file; GET {path}/{transferId} reports its state and GET {path}/{transferId}/file
     * streams the result (triggering a download-timed follow-up statement on first fetch).
     */
    private void buildFileExport(RuntimeContext context, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        String routeId = definition.id();
        Path routeDir = routeFile.source().getParent();
        // The rows an export writes are the document's main source, on every export surface
        // (docs/unified-sources.md, decision 7).
        Path querySql = routeDir.resolve(definition.main().file()).normalize();
        String afterTiming = spec.after() == null ? null : spec.after().effectiveTiming();
        Path afterSql = spec.after() == null
                ? null
                : routeDir.resolve(spec.after().sql().file()).normalize();
        Path template = spec.template() == null
                ? null
                : routeDir.resolve(spec.template()).normalize();

        String served = routeId;
        if (mountRest) {
            mount(context, routeFile.httpMethod(), routeFile.urlPath(), served);
        }
        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, routeFile);
        PipelineBuilder exportStep = route
                .process(new RequestBinder(definition, routeFile.urlPath(),
                        compiledAppHome, functions))
                // The export's own locale, not the requesting browser's (docs/lookups.md,
                // decision 12): a document must not carry names in one language and its
                // numbers and dates in another.
                .process(new io.tesseraql.compiler.binding.CatalogBinder(
                        formatDeclaration(spec.locale(), "tesseraql.files.locale")));
        exportStep = httpSourcesFirst(exportStep, definition);
        exportStep.process(new io.tesseraql.compiler.binding.FileExportStartProcessor(
                routeId, routeFile.urlPath(), appName, spec.format(),
                spec.toWriteSpec(template, appHome),
                formatDeclaration(spec.locale(), "tesseraql.files.locale"),
                formatDeclaration(spec.timezone(), "tesseraql.files.timezone"),
                spec.filename(), querySql, afterTiming, afterSql,
                declaredExportRowCap(spec, spec.format()),
                exportQueries(definition, routeDir), httpSourceNames(definition),
                enrichProcessors(routeDir, definition)));
        mountTransferStatus(context, routeFile, routeId);

        if (mountRest) {
            mount(context, "GET", routeFile.urlPath() + "/{transferId}/file", routeId + ".file");
        }
        PipelineBuilder fileRoute = pipelines.pipeline(routeId + ".file");
        applySecurity(fileRoute, definition.security(), "GET",
                routeFile.urlPath() + "/{transferId}/file");
        fileRoute.process(new io.tesseraql.compiler.binding.FileDownloadProcessor());
    }

    /** The route's locale/timezone declaration, falling back to the app-wide configuration. */
    private String formatDeclaration(String declared, String configKey) {
        return declared != null && !declared.isBlank()
                ? declared
                : config.getString(configKey).orElse(null);
    }

    /** GET {path}/{transferId}: the shared status endpoint, secured like its parent route. */
    private void mountTransferStatus(RuntimeContext context, RouteFile routeFile, String routeId) {
        if (mountRest) {
            mount(context, "GET", routeFile.urlPath() + "/{transferId}", routeId + ".status");
        }
        PipelineBuilder route = pipelines.pipeline(routeId + ".status");
        applySecurity(route, routeFile.definition().security(), "GET",
                routeFile.urlPath() + "/{transferId}");
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
    private void buildTemplatePage(RuntimeContext context, Path appHome, RouteFile routeFile) {
        Path routeDir = routeFile.source().getParent();
        PipelineBuilder route = pipelineThroughSql(context, routeFile);
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
                            ? io.tesseraql.compiler.binding.ViewBinding.of(appHome,
                                    html.view(), routeFile.definition(), this::postRouteByPath,
                                    this::viewPathById)
                            : null;
            // Declarative parts on a hand-owned template (docs/view-composition.md wave 2c):
            // each response.html.views id compiles like a view: reference and publishes its
            // model as views['<id>'].
            java.util.Map<String, io.tesseraql.compiler.binding.ViewBinding> boundViews = new java.util.LinkedHashMap<>();
            if (html != null) {
                for (String id : html.views()) {
                    boundViews.put(id, io.tesseraql.compiler.binding.ViewBinding.of(appHome,
                            id, routeFile.definition(), this::postRouteByPath,
                            this::viewPathById));
                }
            }
            route.process(new HtmlResponseRenderer(withDefaultHeaders(html), appHome,
                    routeDir, i18n.defaultTag(), viewBinding, boundViews, functions)
                    .basePath(basePath()));
        }
        applyHttpCache(route, routeFile.definition());
        // pipelineThroughSql opened the idempotency record; closing it here is what stops a
        // retry with the same key answering 409 for the whole TTL instead of serving the page.
        applyIdempotencyComplete(route, routeFile.definition());
    }

    /** The view registry lookup — the file declaring this view id (docs/view-composition.md). */
    private Path viewPathById(String id) {
        var view = manifest.viewById(id);
        return view == null ? null : view.source();
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
    private PipelineBuilder pipelineThroughSql(RuntimeContext context, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String served = routeId;

        if (mountRest) {
            mount(context, routeFile.httpMethod(), routeFile.urlPath(), served);
        }

        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, routeFile);
        applyIdempotencyBegin(route, definition);
        PipelineBuilder step = route
                .process(new RequestBinder(definition, routeFile.urlPath(),
                        compiledAppHome, functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());
        // Declarative pagination (roadmap Phase 41): compute the page window before the main
        // query executes; the producer appends the dialect clause and publishes `page`.
        if (definition.pagination() != null) {
            step = step
                    .process(new io.tesseraql.compiler.binding.PageBinder(definition.pagination()));
        }
        // Named sources run in authored order, each result keyed under its name — `main`
        // included, which is why there is no primary step beside this loop any more: the
        // primary was the one result that published under a mechanism's name ("sql") instead
        // of its own (docs/unified-sources.md decision 10). A route may declare no source at
        // all (the page recipe: forms, static pages). One loop, because the mechanism is the
        // entry's own arm: two loops meant a SQL source could never read an earlier HTTP one,
        // for no reason a reader of the document could see.
        for (var entry : definition.sources().entrySet()) {
            step = source(step, routeFile, entry.getKey(), entry.getValue());
        }
        // A command whose statements are contract/service calls is not transactional — those
        // run through their own components, not the command's JDBC connection — so its steps
        // execute here, publishing under steps.<id> exactly as the transactional processor's do.
        for (var entry : definition.steps().entrySet()) {
            step = step.process(new io.tesseraql.compiler.binding.NamedQueryBinder(
                    entry.getValue()))
                    .process(execution(routeFile, entry.getValue(), "steps." + entry.getKey()));
        }
        // enrich: runs last of the data stage: it reads a result set the earlier steps
        // published and writes it back enriched (docs/lookups.md).
        step = enrichments(step, routeFile.source().getParent(), definition);
        if (definition.pagination() != null) {
            step = step.process(new io.tesseraql.compiler.binding.PageHeaders());
        }
        return step;
    }

    /** The names of the sources whose arm is an outbound call. */
    private static java.util.Set<String> httpSourceNames(RouteDefinition definition) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        definition.sources().forEach((name, binding) -> {
            if (binding.isHttp()) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * One named source, mounted by the arm it declares: an outbound call rides the gateway
     * processor, anything else executes on a datasource. Both publish the same envelope under
     * the source's name, which is what lets a response, a view or a later source refer to one
     * without knowing how it was fetched.
     */
    private PipelineBuilder source(PipelineBuilder step, RouteFile routeFile,
            String name, io.tesseraql.yaml.model.Binding binding) {
        if (binding.isHttp()) {
            return step.process(new io.tesseraql.compiler.binding.HttpSourceProcessor(
                    name, binding.http()));
        }
        return step
                .process(new io.tesseraql.compiler.binding.NamedQueryBinder(binding))
                .process(execution(routeFile, binding, name));
    }

    /** The same, for the {@code direct:} pipelines that carry a directory instead of a route. */
    private PipelineBuilder source(PipelineBuilder step, Path dir, String name,
            io.tesseraql.yaml.model.Binding binding, String datasource) {
        if (binding.isHttp()) {
            return step.process(new io.tesseraql.compiler.binding.HttpSourceProcessor(
                    name, binding.http()));
        }
        return step
                .process(new io.tesseraql.compiler.binding.NamedQueryBinder(binding))
                .process(execution(dir, binding, name, datasource));
    }

    /**
     * The enrichment stage: each {@code enrich:} entry folds a keyed reference into the rows of
     * the result it names (docs/lookups.md). Entries run in authored order, so one may enrich a
     * result an earlier one already enriched.
     */
    private PipelineBuilder enrichments(PipelineBuilder step, Path routeDir,
            RouteDefinition definition) {
        PipelineBuilder enriched = step;
        for (var processor : enrichProcessors(routeDir, definition)) {
            enriched = enriched.process(processor);
        }
        return enriched;
    }

    /**
     * One {@link io.tesseraql.compiler.binding.EnrichProcessor} per {@code enrich:} entry, in
     * authored order — so a later entry may enrich a result an earlier one already enriched.
     *
     * <p>Built rather than mounted here because an export does not run them as pipeline steps:
     * it folds them into the rows it is writing, a window at a time (docs/lookups.md, slice
     * 13b). One construction either way, so the two paths cannot drift on what a reference is.
     */
    private java.util.List<io.tesseraql.compiler.binding.EnrichProcessor> enrichProcessors(
            Path routeDir, RouteDefinition definition) {
        java.util.List<io.tesseraql.compiler.binding.EnrichProcessor> processors = new java.util.ArrayList<>();
        // An enrichment nests under the source whose rows it folds into (docs/unified-sources.md
        // decision 5), so the target is where it is written, not a name it carries.
        definition.sources().forEach((into, binding) -> binding.enrich().forEach((name, spec) -> {
            if (spec.sql() == null) {
                // A sibling source is already in the context and an HTTP reference has no file,
                // no datasource and no dialect — it rides the outbound gateway, which the
                // processor looks up per request. Neither needs anything compiled here.
                processors.add(new io.tesseraql.compiler.binding.EnrichProcessor(
                        into, name, spec, java.util.List.of(), null, null, null,
                        commandBounds()));
                return;
            }
            String datasource = bindingDatasource(io.tesseraql.yaml.model.Binding.sql(spec.sql()),
                    definition.effectiveDatasource());
            String dialect = datasourceDialect(datasource);
            Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                    routeDir.resolve(spec.sql().file()).normalize(), dialect);
            processors.add(new io.tesseraql.compiler.binding.EnrichProcessor(
                    into, name, spec, parseSql(file), file.toString(), datasource, dialect,
                    commandBounds()));
        }));
        return processors;
    }

    /** Parses a 2-way SQL file at build time, so a broken reference query never reaches a request. */
    private java.util.List<io.tesseraql.core.sql.SqlNode> parseSql(Path file) {
        try {
            return io.tesseraql.core.sql.Sql2WayParser.parse(java.nio.file.Files.readString(file),
                    functions);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /**
     * Builds an application-declared MCP tool (roadmap Phase 24 follow-on) as a {@code direct:}
     * route, never mounted on HTTP. The route is the same pipeline a {@code query-json} /
     * {@code command-json} route runs - telemetry, the tool's own security (auth + policy), input
     * binding and validation, SQL or the transactional command - so a tool is governed exactly like
     * a route. The runtime's MCP endpoint sends to {@code direct:mcp.<id>} and reads the JSON result.
     */
    private void buildMcpTool(RuntimeContext context, ToolFile toolFile) {
        RouteDefinition definition = toolFile.definition();
        Path toolDir = toolFile.source().getParent();
        String routeId = "mcp." + definition.id();

        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, definition.id(), "MCP", "/" + definition.id(), definition);
        applyIdempotencyBegin(route, definition);
        PipelineBuilder step = route
                .process(new RequestBinder(definition, null, compiledAppHome,
                        functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());

        boolean transactional = usesTransactionalCommand(definition);
        if (transactional) {
            step = httpSourcesFirst(step, definition);
            step = step.process(commandProcessor(routeId, definition, toolDir, null));
        } else {
            for (var entry : definition.sources().entrySet()) {
                step = source(step, toolDir, entry.getKey(), entry.getValue(),
                        definition.effectiveDatasource());
            }
        }
        // Same placement as an HTTP command's: after the write, so a rollback bypasses it. A
        // tool that changes data has the same reason to refresh a live view that a route does,
        // and emit: was accepted here while doing nothing at all.
        if (!definition.emit().isEmpty()) {
            step = step.process(new io.tesseraql.compiler.binding.TopicEmitProcessor(
                    definition.emit()));
        }
        if (!definition.invalidates().isEmpty()) {
            step = step.process(new io.tesseraql.compiler.binding.CatalogInvalidateProcessor(
                    definition.invalidates()));
        }
        // The read pipeline already mounted every source above; only a transactional tool still
        // owes its named queries, and its http: sources already ran before the command. Without
        // both guards every source here mounted twice — a read tool executed its whole pipeline
        // two times per invocation, and a command tool called its partner again after the commit.
        if (transactional) {
            for (var entry : definition.sources().entrySet()) {
                if (entry.getValue().isHttp()) {
                    continue;
                }
                step = source(step, toolDir, entry.getKey(), entry.getValue(),
                        definition.effectiveDatasource());
            }
        }
        step = enrichments(step, toolDir, definition);
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
    private void buildMcpResource(RuntimeContext context, ResourceFile resourceFile) {
        RouteDefinition definition = resourceFile.definition();
        Path resourceDir = resourceFile.source().getParent();
        String routeId = "mcp.resource." + definition.id();

        PipelineBuilder route = pipelines.pipeline(routeId);
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                definition.id(), "MCP-RESOURCE", "/" + definition.id(), appName));
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security(), "GET", null);
        applyTenancy(route);
        applyI18n(route);
        PipelineBuilder step = route
                .process(new RequestBinder(definition, null, compiledAppHome,
                        functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());
        for (var entry : definition.sources().entrySet()) {
            step = source(step, resourceDir, entry.getKey(), entry.getValue(),
                    definition.effectiveDatasource());
        }
        step = enrichments(step, resourceDir, definition);
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
    private void buildMcpUi(RuntimeContext context, Path appHome, UiResourceFile uiFile) {
        RouteDefinition definition = uiFile.definition();
        Path uiDir = uiFile.source().getParent();
        String routeId = "mcp.ui." + definition.id();

        PipelineBuilder route = pipelines.pipeline(routeId);
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                definition.id(), "MCP-UI", "/" + definition.id(), appName));
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security(), "GET", null);
        applyTenancy(route);
        applyI18n(route);
        PipelineBuilder step = route
                .process(new RequestBinder(definition, null, compiledAppHome,
                        functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());
        for (var entry : definition.sources().entrySet()) {
            step = source(step, uiDir, entry.getKey(), entry.getValue(),
                    definition.effectiveDatasource());
        }
        step = enrichments(step, uiDir, definition);
        step.process(new HtmlResponseRenderer(withDefaultHeaders(definition.response().html()),
                appHome, uiDir, i18n.defaultTag(), null, java.util.Map.of(), functions)
                .basePath(basePath()));
    }

    /**
     * Builds an application-declared MCP prompt (docs/prompt-as-recipe.md) as a read-only
     * {@code direct:} route, never mounted on HTTP. It runs the same head every recipe runs -
     * telemetry, audit, the prompt's own security (auth + policy), tenancy and locale resolution,
     * input binding, then the declared {@code sources:} - and ends in the {@code text:} renderer,
     * whose rendered string is the message. The runtime's MCP endpoint sends to
     * {@code direct:mcp.prompt.<id>} on {@code prompts/get} and wraps the body as one
     * {@code user} message.
     *
     * <p>The recipe is a read: {@code prompts/get} is a read in the protocol's own vocabulary, so
     * a command step is refused rather than compiled into a prompt that writes.
     */
    private void buildMcpPrompt(RuntimeContext context,
            io.tesseraql.yaml.manifest.PromptFile promptFile) {
        RouteDefinition definition = promptFile.definition();
        Path promptDir = promptFile.source().getParent();
        if (!"prompt-text".equals(definition.recipe())) {
            throw new TqlException(UNSUPPORTED_RECIPE, "Prompt '" + definition.id()
                    + "': unknown recipe '" + definition.recipe()
                    + "'; a prompt document uses prompt-text");
        }
        if (!definition.steps().isEmpty()) {
            throw new TqlException(PROMPT_WRITES, "Prompt '" + definition.id()
                    + "' declares steps: — prompts/get is a read, so a prompt renders text from"
                    + " sources: and a prompt that writes is a tool");
        }
        if (definition.response() == null || definition.response().text() == null) {
            throw new TqlException(PROMPT_WITHOUT_TEXT, "Prompt '" + definition.id()
                    + "' declares no response.text: — the rendered text is the message it"
                    + " returns, so there is nothing to answer with");
        }
        String routeId = "mcp.prompt." + definition.id();

        PipelineBuilder route = pipelines.pipeline(routeId);
        applyCommonGovernance(route, definition.id(), "MCP-PROMPT", "/" + definition.id(),
                definition);
        PipelineBuilder step = route
                .process(new RequestBinder(definition, null, compiledAppHome,
                        functions))
                .process(new io.tesseraql.compiler.binding.CatalogBinder());
        for (var entry : definition.sources().entrySet()) {
            step = source(step, promptDir, entry.getKey(), entry.getValue(),
                    definition.effectiveDatasource());
        }
        step = enrichments(step, promptDir, definition);
        step.process(new io.tesseraql.compiler.binding.TextResponseRenderer(
                definition.response().text(), compiledAppHome, promptDir));
    }

    /** A tool's result renderer: its declared JSON shape, or the raw SQL/command result. */
    private io.tesseraql.pipeline.Step mcpToolRenderer(RouteDefinition definition) {
        if (definition.response() != null && definition.response().json() != null) {
            return new JsonResponseRenderer(withDefaultHeaders(definition.response().json()),
                    functions);
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
    private io.tesseraql.pipeline.Step execution(RouteFile routeFile,
            io.tesseraql.yaml.model.Binding binding, String resultKey) {
        return execution(routeFile.source().getParent(), binding, resultKey,
                routeFile.definition().effectiveDatasource());
    }

    /** As {@link #executionUri(RouteFile, io.tesseraql.yaml.model.Binding, String)}, resolving
     * SQL files relative to {@code sourceDir} (shared by routes and MCP tools). The binding's own
     * {@code datasource:} wins over {@code routeDatasource}, the route-level connector (roadmap
     * Phase 53); the baked dialect follows the connector the SQL actually runs on. */
    private io.tesseraql.pipeline.Step execution(Path sourceDir,
            io.tesseraql.yaml.model.Binding binding, String resultKey, String routeDatasource) {
        if (binding.isService()) {
            return new io.tesseraql.pipeline.service.ServiceStep("call", binding.service(),
                    resultKey);
        }
        if (binding.isContract()) {
            return new io.tesseraql.pipeline.iam.IamStep("contract", binding.contract(),
                    binding.effectiveMode(), resultKey);
        }
        String datasource = bindingDatasource(binding, routeDatasource);
        Path sqlPath = sourceDir.resolve(binding.file()).normalize();
        // The dialect is the load-bearing setting: the step resolves foo.<dialect>.sql variants
        // from it, picks the dialect's streaming profile, and folds column labels with it.
        return new io.tesseraql.pipeline.sql.SqlStep(sqlPath.toString(), datasource,
                binding.effectiveMode(), resultKey, effectiveMaxRows(binding),
                effectiveTimeoutSeconds(binding), effectiveOnOverflow(binding), null,
                datasourceDialect(datasource));
    }

    /** The connector a binding runs on: its own {@code datasource:} when declared, else the route's. */
    private static String bindingDatasource(io.tesseraql.yaml.model.Binding binding,
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
    private int effectiveTimeoutSeconds(io.tesseraql.yaml.model.Binding binding) {
        if (binding.timeoutSeconds() != null) {
            return Math.max(0, binding.timeoutSeconds());
        }
        return defaultTimeoutSeconds();
    }

    /**
     * The app-wide statement timeout: {@code tesseraql.sql.timeoutSeconds}, else 30. The one
     * resolution, so a binding's endpoint and a command's own bounds cannot default apart.
     */
    private int defaultTimeoutSeconds() {
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
                || (definition.main() != null && definition.main().isSequence())
                || definition.steps().values().stream()
                        .anyMatch(io.tesseraql.yaml.model.Binding::isSequence);
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
    private void applyCommonGovernance(PipelineBuilder route, String id, String method,
            String path, RouteDefinition definition) {
        applyTelemetry(route, id, method, path);
        applyAudit(route, id, method, path, definition);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security(), method, path);
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
    private void applyAttachmentGovernance(PipelineBuilder route, String id, String method,
            String path, SecuritySpec security) {
        applyTelemetry(route, id, method, path);
        applySecurity(route, security, method, path);
        applyTenancy(route);
        applyI18n(route);
    }

    private void applyCommonGovernance(PipelineBuilder route, RouteFile routeFile) {
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
    private void applyTelemetry(PipelineBuilder route, String id, String method,
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
    private void applyAudit(PipelineBuilder route, String id, String method, String path,
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
    private void applyLane(PipelineBuilder route, RouteDefinition definition) {
        if (definition.admission() == null || definition.admission().lane() == null) {
            return;
        }
        String lane = definition.admission().lane();
        route.process(new io.tesseraql.compiler.binding.LaneGate(lane));
        route.lane(TesseraqlProperties.laneExecutorRef(lane));
    }

    /** Resolves and propagates the request tenant when tenancy is enabled (design ch. 30). */
    private void applyTenancy(PipelineBuilder route) {
        if (tenancy.enabled()) {
            route.process(new io.tesseraql.compiler.binding.TenantResolution(tenancy));
        }
    }

    /** Resolves the request locale after authentication, before binding (roadmap Phase 22). */
    private void applyI18n(PipelineBuilder route) {
        route.process(new io.tesseraql.compiler.binding.LocaleResolution(i18n));
    }

    /** Inserts per-route rate limit and concurrency guards when declared (design ch. 36.1). */
    private void applyConcurrency(PipelineBuilder route, RouteDefinition definition) {
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
    private void applyIdempotencyBegin(PipelineBuilder route, RouteDefinition definition) {
        IdempotencySpec idempotency = definition.idempotency();
        if (idempotency == null) {
            return;
        }
        String scope = idempotency.scope() != null ? idempotency.scope() : definition.id();
        long ttl = idempotency.ttl() != null
                ? Durations.toMillis(idempotency.ttl())
                : DEFAULT_IDEMPOTENCY_TTL;
        route.process(IdempotencyProcessors.begin(scope, ttl, idempotency.isRequired()));
        // A replay has already been answered: stop here rather than re-running the write. Said
        // as "stop the route" rather than as a one-armed choice, because that is what it means
        // and because a route that is a plain chain is one the runtime's edge can run
        // (docs/http-edge.md decision 2).
        route.process(exchange -> {
            if (Boolean.TRUE.equals(
                    exchange.getProperty(IdempotencyProcessors.REPLAY_PROPERTY))) {
                exchange.setRouteStop(true);
            }
        });
    }

    /** Appends the idempotency complete step after the response is rendered. */
    private void applyIdempotencyComplete(PipelineBuilder route,
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
    private io.tesseraql.compiler.binding.ExecutionBounds commandBounds() {
        int maxRows = config.getString("tesseraql.resultMaterialization.maxRows")
                .map(Integer::parseInt)
                .orElse(DEFAULT_MAX_ROWS);
        String onOverflow = config.getString("tesseraql.resultMaterialization.onOverflow")
                .orElse("fail");
        return new io.tesseraql.compiler.binding.ExecutionBounds(
                defaultTimeoutSeconds(), maxRows, onOverflow);
    }

    /**
     * The document's other data — the order header, the totals — resolved against the declaring
     * directory. These are the sources beside {@code main}: an export writes {@code main}'s rows
     * and a template composes the rest around them, so they run on the extraction's own
     * connection and the executing path receives the files rather than a second set of steps.
     */
    private static java.util.List<io.tesseraql.core.files.ExportQuery> exportQueries(
            RouteDefinition definition, Path dir) {
        java.util.List<io.tesseraql.core.files.ExportQuery> queries = new java.util.ArrayList<>();
        definition.sources().forEach((name, binding) -> {
            if (!RouteDefinition.MAIN.equals(name) && binding.file() != null) {
                queries.add(new io.tesseraql.core.files.ExportQuery(name,
                        dir.resolve(binding.file()).normalize()));
            }
        });
        return java.util.List.copyOf(queries);
    }

    /**
     * The ceiling an export declares (docs/export-pipeline.md, decision 7): the export's own
     * override, then the materializing-query configuration, then the default. Whether it applies
     * at all is the executing path's question, asked of the codec through
     * {@link io.tesseraql.core.files.FileCodec#streams} — a streaming export is never capped.
     */
    private io.tesseraql.core.files.ExportRowCap declaredExportRowCap(
            io.tesseraql.yaml.model.ExportSpec spec, String format) {
        int maxRows = spec != null && spec.maxRows() != null
                ? spec.maxRows()
                : config.getString("tesseraql.resultMaterialization.maxRows")
                        .map(Integer::parseInt)
                        .orElse(DEFAULT_MAX_ROWS);
        String onOverflow = spec != null && spec.onOverflow() != null
                ? spec.onOverflow()
                : config.getString("tesseraql.resultMaterialization.onOverflow").orElse("fail");
        return new io.tesseraql.core.files.ExportRowCap(maxRows, onOverflow, format);
    }

    /** Resolves the effective row cap: route override, then global config, then default (ch. 28.7). */
    private int effectiveMaxRows(Binding sql) {
        if (sql.materialize() != null && sql.materialize().maxRows() != null) {
            return sql.materialize().maxRows();
        }
        return config.getString("tesseraql.resultMaterialization.maxRows")
                .map(Integer::parseInt)
                .orElse(DEFAULT_MAX_ROWS);
    }

    private String effectiveOnOverflow(Binding sql) {
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

    /**
     * The same merge for a JSON response.
     *
     * <p>The defaults used to reach HTML, file and stream responses only, on the reading that the
     * block is browser-document machinery. Three of the four are — {@code Content-Security-Policy},
     * {@code X-Frame-Options} and {@code Referrer-Policy} govern a document and are inert on a
     * response no browser renders as one. {@code X-Content-Type-Options: nosniff} is the exception,
     * and it is the header whose whole purpose is to stop a browser treating a non-document as a
     * document: a JSON body was the one place it was most needed and the one place it never
     * arrived. Merging the whole block rather than a classified subset keeps one mechanism — the
     * alternative reads header names to guess which are document-scoped, which an operator adding
     * a header of their own could not predict.
     */
    private io.tesseraql.yaml.model.ResponseSpec.JsonResponse withDefaultHeaders(
            io.tesseraql.yaml.model.ResponseSpec.JsonResponse json) {
        if (json == null) {
            return null;
        }
        return json.withHeaders(responseHeaders.mergeUnder(json.headers()));
    }

    /** Inserts authenticate/authorize steps before binding when the route declares security. */
    private void applySecurity(PipelineBuilder route, SecuritySpec security,
            String httpMethod, String urlPath) {
        if (security == null) {
            return;
        }
        if (security.auth() != null && !"public".equals(security.auth())) {
            route.process(new AuthStep("authenticate", security.auth(), null, null));
            // The member fence (docs/stack-shells.md structural decision 3): on a hosted stack
            // member the runtime binds the topology bean and an authenticated principal without
            // the member's app-use atom is refused before the route runs; everywhere else the
            // bean is absent and this is a no-op. Emitted here rather than decided here because
            // only the runtime knows its own topology, and public routes stay untouched.
            route.process(new AuthStep("fence"));
            // Grant context conditions (docs/access-governance.md structural decision 8): a
            // held role whose network or hours conditions this request does not satisfy leaves
            // the principal here, before activation chooses among what is left. Unlike its two
            // neighbours this step has no topology guard — a role's conditions belong to the
            // grant, not to the stack, so the unhosted boot evaluates them too.
            route.process(new AuthStep("conditions"));
            // Role activation (docs/application-roles.md structural decision 4): after the
            // union-scoped fence, the acting-role signal narrows the exchange's principal to
            // the active view everything downstream reads. Same topology guard as the fence —
            // absent bean, no-op — so the unhosted boot serves no activation step.
            route.process(new AuthStep("activate"));
        }
        if (security.csrfEnforced(httpMethod)) {
            route.process(new AuthStep("csrf"));
        }
        if (security.policy() != null && !security.policy().isBlank()) {
            route.process(authorize(security.policy(), urlPath));
        }
    }

    /**
     * The {@code policy=} value for the authorize endpoint, and the route's URL template beside
     * it when the policy resolves from the path.
     *
     * <p>A fixed policy id goes on the URI as it always has, byte for byte. A policy that
     * resolves an atom from the request (docs/access-governance.md structural decision 7) is
     * refused here when it cannot resolve — the same rule the linter reports, so a configuration
     * that never saw the linter still fails at its source rather than as a puzzling 403 at
     * request time. The {@code path.} qualifier is dropped and the route's own template travels
     * with it, so the producer reads the value off the request's URL: the router's headers
     * would be the shorter path, but a form field named after the path parameter overwrites
     * one, and the gate would then resolve from the caller's own body.
     *
     * <p>Both are percent-encoded because braces are not URI characters.
     */
    private AuthStep authorize(String policy, String urlPath) {
        if (!io.tesseraql.security.policy.PolicyTemplate.isTemplate(policy)) {
            return new AuthStep("authorize", null, policy, null);
        }
        String violation = io.tesseraql.yaml.app.PolicyCodes.templateViolation(policy,
                urlPath == null ? java.util.List.of() : pathParams(urlPath));
        if (violation != null) {
            throw new TqlException(io.tesseraql.yaml.app.PolicyCodes.TEMPLATE_UNRESOLVABLE,
                    violation);
        }
        // The atom and the template are arguments now, so nothing is percent-encoded into a
        // query string and nothing has to be decoded back out (docs/camel-removal.md decision 2).
        return new AuthStep("authorize", null, policy.replace("{path.", "{"), urlPath);
    }

    /**
     * The prefix every route of this application mounts under and every URL it emits carries
     * ({@code tesseraql.http.basePath}, docs/base-path.md).
     *
     * <p>Normalized to either the empty string or a leading-slash, no-trailing-slash form, so
     * concatenation with a route path is always well-formed. Unset is the empty string, which
     * makes every existing deployment byte-identical.
     */
    String basePath() {
        if (basePath == null) {
            basePath = io.tesseraql.core.http.BasePaths.normalize(
                    config == null
                            ? null
                            : config.getString("tesseraql.http.basePath")
                                    .orElse(null));
        }
        return basePath;
    }

    /**
     * Records where a route answers (docs/http-edge.md decision 1).
     *
     * <p>The router-facing template swaps non-wire-safe parameter names for positional stand-ins;
     * the RequestBinder maps them back to the declared names (WireNames). The base path is not
     * put on here: it used to arrive from the REST configuration this replaces, and the runtime's
     * edge applies it at the mount, which is the one place that now knows about URLs at all
     * (docs/base-path.md decision 5).
     */
    private void mount(RuntimeContext context, String method, String path, String pipeline) {
        String wirePath = io.tesseraql.compiler.binding.WireNames.wirePath(path);
        switch (method) {
            case "GET", "POST", "PUT", "PATCH", "DELETE" -> io.tesseraql.pipeline.HttpMounts
                    .mount(context, method, wirePath, pipeline);
            default ->
                throw new TqlException(UNSUPPORTED_RECIPE, "Unsupported HTTP method: " + method);
        }
    }
}
