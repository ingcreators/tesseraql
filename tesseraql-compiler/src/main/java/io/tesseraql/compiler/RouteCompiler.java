package io.tesseraql.compiler;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ConcurrencyLimiter;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.binding.HtmlResponseRenderer;
import io.tesseraql.compiler.binding.IdempotencyProcessors;
import io.tesseraql.compiler.binding.JsonResponseRenderer;
import io.tesseraql.compiler.binding.OutboxCommandProcessor;
import io.tesseraql.compiler.binding.RateLimiter;
import io.tesseraql.compiler.binding.RequestBinder;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
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

    private static final System.Logger LOG = System.getLogger(RouteCompiler.class.getName());
    private static final TqlErrorCode UNSUPPORTED_RECIPE = new TqlErrorCode(TqlDomain.CAMEL, 3100);
    private static final String DEFAULT_DATASOURCE = "main";
    private static final int DEFAULT_MAX_ROWS = 10_000;
    private static final long DEFAULT_IDEMPOTENCY_TTL = java.time.Duration.ofHours(24).toMillis();

    private AppConfig config;
    private io.tesseraql.compiler.binding.TenancySettings tenancy;

    /** Builds a Camel {@link RouteBuilder} for all routes in the manifest. */
    public RouteBuilder compile(AppManifest manifest) {
        this.config = manifest.config();
        this.tenancy = io.tesseraql.compiler.binding.TenancySettings.from(config);
        return new RouteBuilder() {
            @Override
            public void configure() {
                restConfiguration().component("platform-http");
                onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
                onException(Exception.class).handled(true).process(new ErrorResponseRenderer());
                for (RouteFile routeFile : manifest.routes()) {
                    buildRoute(this, manifest.appHome(), routeFile);
                }
            }
        };
    }

    private void buildRoute(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        switch (definition.recipe()) {
            case "query-json", "command-json" -> buildJson(builder, routeFile);
            case "query-html" -> buildQueryHtml(builder, appHome, routeFile);
            case "query-export" -> buildQueryExport(builder, routeFile);
            default -> LOG.log(System.Logger.Level.WARNING,
                    // Recipes not yet implemented are skipped so a mixed-recipe app can still boot.
                    "Skipping route {0}: recipe ''{1}'' is not supported yet",
                    definition.id(), definition.recipe());
        }
    }

    private void buildJson(RouteBuilder builder, RouteFile routeFile) {
        if (routeFile.definition().outbox() != null) {
            buildCommandWithOutbox(builder, routeFile);
            return;
        }
        ProcessorDefinition<?> route = pipelineThroughSql(builder, routeFile)
                .process(new JsonResponseRenderer(routeFile.definition().response().json()));
        applyIdempotencyComplete(route, routeFile.definition());
    }

    /** Builds a command route whose SQL and outbox event commit atomically (design ch. 39.2). */
    private void buildCommandWithOutbox(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;
        restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);

        Path sqlPath = routeFile.source().getParent().resolve(definition.sql().file()).normalize();

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyTelemetry(route, routeFile);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyIdempotencyBegin(route, definition);
        route.process(new RequestBinder(definition, pathParams(routeFile.urlPath())))
                .process(new OutboxCommandProcessor(sqlPath, DEFAULT_DATASOURCE, definition.outbox()))
                .process(new JsonResponseRenderer(definition.response().json()));
        applyIdempotencyComplete(route, definition);
    }

    private void buildQueryExport(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;
        restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);

        Path sqlPath = routeFile.source().getParent().resolve(definition.sql().file()).normalize();
        String sqlUri = "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + DEFAULT_DATASOURCE
                + "&mode=query-export&format=csv&filename=" + exportFilename(definition);

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyTelemetry(route, routeFile);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        route.process(new RequestBinder(definition, pathParams(routeFile.urlPath()))).to(sqlUri);
    }

    private static String exportFilename(RouteDefinition definition) {
        if (definition.response() != null && definition.response().stream() != null
                && definition.response().stream().filename() != null) {
            return definition.response().stream().filename();
        }
        return definition.id() + ".csv";
    }

    private void buildQueryHtml(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        Path templateRoot = appHome.resolve("templates");
        pipelineThroughSql(builder, routeFile)
                .process(new HtmlResponseRenderer(
                        routeFile.definition().response().html(), templateRoot));
    }

    /** Builds the common route head: REST endpoint, security, request binding, SQL execution. */
    private ProcessorDefinition<?> pipelineThroughSql(RouteBuilder builder, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;

        restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applyTelemetry(route, routeFile);
        applyConcurrency(route, definition);
        applyLane(route, definition);
        applySecurity(route, definition.security());
        applyTenancy(route);
        applyIdempotencyBegin(route, definition);
        return route.process(new RequestBinder(definition, pathParams(routeFile.urlPath()))).to(executionUri(routeFile));
    }

    /** Extracts {@code {name}} path-parameter names from a URL template. */
    private static java.util.List<String> pathParams(String urlPath) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\w+)\\}").matcher(urlPath);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Builds the execution step URI: the tesseraql-iam contract or a tesseraql-sql file. */
    private String executionUri(RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        if (definition.sql().isContract()) {
            return "tesseraql-iam:contract?name=" + definition.sql().contract()
                    + "&mode=" + definition.sql().effectiveMode() + "&resultKey=sql";
        }
        Path sqlPath = routeFile.source().getParent().resolve(definition.sql().file()).normalize();
        return "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + DEFAULT_DATASOURCE
                + "&mode=" + definition.sql().effectiveMode()
                + "&resultKey=sql"
                + "&dialect=" + datasourceDialect()
                + "&maxRows=" + effectiveMaxRows(definition.sql())
                + "&onOverflow=" + effectiveOnOverflow(definition.sql());
    }

    /** Resolves the configured datasource dialect, inferring it from the JDBC URL when unset. */
    private String datasourceDialect() {
        String prefix = "tesseraql.datasources." + DEFAULT_DATASOURCE + ".";
        return config.getString(prefix + "dialect").orElseGet(() ->
                io.tesseraql.core.dialect.Dialect.fromJdbcUrl(config.getString(prefix + "jdbcUrl").orElse(""))
                        .map(io.tesseraql.core.dialect.Dialect::id)
                        .orElse(""));
    }

    /** Inserts the route telemetry step (span + invocation counter) at the route head (ch. 25). */
    private void applyTelemetry(ProcessorDefinition<?> route, RouteFile routeFile) {
        route.process(new io.tesseraql.compiler.binding.RouteTelemetry(
                routeFile.definition().id(), routeFile.httpMethod(), routeFile.urlPath()));
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
        long ttl = idempotency.ttl() != null ? Durations.toMillis(idempotency.ttl()) : DEFAULT_IDEMPOTENCY_TTL;
        route.process(IdempotencyProcessors.begin(scope, ttl, idempotency.isRequired()));
        route.choice()
                .when((org.apache.camel.Predicate) exchange ->
                        Boolean.TRUE.equals(exchange.getProperty(IdempotencyProcessors.REPLAY_PROPERTY)))
                .stop()
                .end();
    }

    /** Appends the idempotency complete step after the response is rendered. */
    private void applyIdempotencyComplete(ProcessorDefinition<?> route, RouteDefinition definition) {
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
            default -> throw new TqlException(UNSUPPORTED_RECIPE, "Unsupported HTTP method: " + method);
        };
    }
}
