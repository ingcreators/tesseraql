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
import io.tesseraql.yaml.model.IdempotencySpec;
import io.tesseraql.yaml.model.PolicySpec;
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
    private static final String DEFAULT_DATASOURCE = "main";
    private static final int DEFAULT_MAX_ROWS = 10_000;
    private static final long DEFAULT_IDEMPOTENCY_TTL = java.time.Duration.ofHours(24).toMillis();

    private AppConfig config;
    private io.tesseraql.compiler.binding.TenancySettings tenancy;
    private io.tesseraql.compiler.binding.I18nSettings i18n;
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
        this.tenancy = io.tesseraql.compiler.binding.TenancySettings.from(config);
        this.i18n = io.tesseraql.compiler.binding.I18nSettings.from(config, manifest.appHome());
        this.mountRest = mountRest;
        if (this.appName == null) {
            this.appName = config.getString("tesseraql.app.name").orElse("app");
        }
        return new RouteBuilder() {
            @Override
            public void configure() {
                if (mountRest) {
                    restConfiguration().component("platform-http");
                }
                onException(TqlException.class).handled(true)
                        .process(new ErrorResponseRenderer(i18n));
                onException(Exception.class).handled(true)
                        .process(new ErrorResponseRenderer(i18n));
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
            }
        };
    }

    private void buildRoute(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        switch (definition.recipe()) {
            case "query-json", "command-json" -> buildJson(builder, routeFile);
            case "query-html", "page" -> buildTemplatePage(builder, appHome, routeFile);
            case "query-export" -> buildQueryExport(builder, appHome, routeFile);
            case "file-import" -> buildFileImport(builder, routeFile);
            case "file-export" -> buildFileExport(builder, appHome, routeFile);
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
        ProcessorDefinition<?> route = pipelineThroughSql(builder, routeFile)
                .process(responseRenderer(routeFile.definition()));
        applyIdempotencyComplete(route, routeFile.definition());
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
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;
        if (mountRest) {
            restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);
        }

        Path routeDir = routeFile.source().getParent();
        String dialect = datasourceDialect();
        java.util.function.Function<String, Path> stepFile = file -> io.tesseraql.core.dialect.DialectSqlResolver
                .resolve(routeDir.resolve(file).normalize(), dialect);

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyTelemetry(route, routeFile);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyI18n(route);
        applyIdempotencyBegin(route, definition);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, pathParams(routeFile.urlPath())))
                .process(new io.tesseraql.compiler.binding.TransactionalCommandProcessor(
                        routeId, definition.sql(), definition.steps(), definition.validate(),
                        definition.notifications(), stepFile, DEFAULT_DATASOURCE, dialect,
                        definition.outbox(), definition.errors(), appName));
        // Named queries still run after the command (outside its transaction), in authored order.
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(routeFile, entry.getValue(), entry.getKey()));
        }
        step.process(responseRenderer(definition));
        applyIdempotencyComplete(step, definition);
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
        String sqlUri = "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + DEFAULT_DATASOURCE
                + "&mode=query-export&filename=" + exportFilename(definition, codec);

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyTelemetry(route, routeFile);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyI18n(route);
        route.process(new RequestBinder(definition, pathParams(routeFile.urlPath())))
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
        applyTelemetry(route, routeFile);
        applySecurity(route, definition.security());
        applyI18n(route);
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
        applyTelemetry(route, routeFile);
        applySecurity(route, definition.security());
        applyI18n(route);
        route.process(new RequestBinder(definition, pathParams(routeFile.urlPath())))
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
        applySecurity(fileRoute, definition.security());
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
        applySecurity(route, routeFile.definition().security());
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
            route.process(new HtmlResponseRenderer(
                    routeFile.definition().response().html(), appHome, routeDir,
                    i18n.defaultTag()));
        }
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
        applyTelemetry(route, routeFile);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyI18n(route);
        applyIdempotencyBegin(route, definition);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, pathParams(routeFile.urlPath())));
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
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                definition.id(), "MCP", "/" + definition.id(), appName));
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyI18n(route);
        applyIdempotencyBegin(route, definition);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, java.util.List.of()));

        if (usesTransactionalCommand(definition)) {
            String dialect = datasourceDialect();
            java.util.function.Function<String, Path> stepFile = file -> io.tesseraql.core.dialect.DialectSqlResolver
                    .resolve(toolDir.resolve(file).normalize(), dialect);
            step = step.process(new io.tesseraql.compiler.binding.TransactionalCommandProcessor(
                    routeId, definition.sql(), definition.steps(), definition.validate(),
                    definition.notifications(), stepFile, DEFAULT_DATASOURCE, dialect,
                    definition.outbox(), definition.errors(), appName));
        } else if (definition.sql() != null) {
            step = step.to(executionUri(toolDir, definition.sql(), "sql"));
        }
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(toolDir, entry.getValue(), entry.getKey()));
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
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyI18n(route);
        ProcessorDefinition<?> step = route
                .process(new RequestBinder(definition, java.util.List.of()));
        if (definition.sql() != null) {
            step = step.to(executionUri(resourceDir, definition.sql(), "sql"));
        }
        for (var entry : definition.queries().entrySet()) {
            step = step
                    .process(new io.tesseraql.compiler.binding.NamedQueryBinder(entry.getValue()))
                    .to(executionUri(resourceDir, entry.getValue(), entry.getKey()));
        }
        step.process(mcpToolRenderer(definition));
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
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\w+)\\}")
                .matcher(urlPath);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Builds an execution step URI: a service provider, a tesseraql-iam contract or a SQL file. */
    private String executionUri(RouteFile routeFile, io.tesseraql.yaml.model.SqlBinding binding,
            String resultKey) {
        return executionUri(routeFile.source().getParent(), binding, resultKey);
    }

    /** As {@link #executionUri(RouteFile, io.tesseraql.yaml.model.SqlBinding, String)}, resolving
     * SQL files relative to {@code sourceDir} (shared by routes and MCP tools). */
    private String executionUri(Path sourceDir, io.tesseraql.yaml.model.SqlBinding binding,
            String resultKey) {
        if (binding.isService()) {
            return "tesseraql-service:call?name=" + binding.service() + "&resultKey=" + resultKey;
        }
        if (binding.isContract()) {
            return "tesseraql-iam:contract?name=" + binding.contract()
                    + "&mode=" + binding.effectiveMode() + "&resultKey=" + resultKey;
        }
        Path sqlPath = sourceDir.resolve(binding.file()).normalize();
        return "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + DEFAULT_DATASOURCE
                + "&mode=" + binding.effectiveMode()
                + "&resultKey=" + resultKey
                + "&dialect=" + datasourceDialect()
                + "&maxRows=" + effectiveMaxRows(binding)
                + "&onOverflow=" + effectiveOnOverflow(binding);
    }

    /** Resolves the configured datasource dialect, inferring it from the JDBC URL when unset. */
    private String datasourceDialect() {
        String prefix = "tesseraql.datasources." + DEFAULT_DATASOURCE + ".";
        return config.getString(prefix + "dialect")
                .orElseGet(() -> io.tesseraql.core.dialect.Dialect
                        .fromJdbcUrl(config.getString(prefix + "jdbcUrl").orElse(""))
                        .map(io.tesseraql.core.dialect.Dialect::id)
                        .orElse(""));
    }

    /** Inserts the route telemetry step (span + invocation counter) at the route head (ch. 25). */
    private void applyTelemetry(ProcessorDefinition<?> route, RouteFile routeFile) {
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                routeFile.definition().id(), routeFile.httpMethod(), routeFile.urlPath(), appName));
    }

    /**
     * Dispatches the route onto its declared execution lane (design ch. 24): a backpressure gate
     * followed by a {@code threads()} handoff to the lane's executor, so the remaining steps run on
     * a virtual (or platform) thread.
     */
    private void applyLane(ProcessorDefinition<?> route, RouteDefinition definition) {
        if (definition.policy() == null || definition.policy().lane() == null) {
            return;
        }
        String lane = definition.policy().lane();
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
        if (definition.policy() == null) {
            return;
        }
        PolicySpec.RateLimit rateLimit = definition.policy().rateLimit();
        if (rateLimit != null && rateLimit.requestsPerSecond() != null) {
            int rps = rateLimit.requestsPerSecond();
            int burst = rateLimit.burst() != null ? rateLimit.burst() : rps;
            route.process(new RateLimiter(rps, burst).acquire());
        }
        PolicySpec.Concurrency concurrency = definition.policy().concurrency();
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

    /** Inserts authenticate/authorize steps before binding when the route declares security. */
    private void applySecurity(ProcessorDefinition<?> route, SecuritySpec security) {
        if (security == null) {
            return;
        }
        if (security.auth() != null && !"public".equals(security.auth())) {
            route.to("tesseraql-auth:authenticate?auth=" + security.auth());
        }
        if (Boolean.TRUE.equals(security.csrf())) {
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
