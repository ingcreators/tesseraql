package io.tesseraql.compiler;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.binding.HtmlResponseRenderer;
import io.tesseraql.compiler.binding.JsonResponseRenderer;
import io.tesseraql.compiler.binding.RequestBinder;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SecuritySpec;
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

    /** Builds a Camel {@link RouteBuilder} for all routes in the manifest. */
    public RouteBuilder compile(AppManifest manifest) {
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
            case "query-json" -> buildQueryJson(builder, routeFile);
            case "query-html" -> buildQueryHtml(builder, appHome, routeFile);
            default -> LOG.log(System.Logger.Level.WARNING,
                    // Recipes not yet implemented are skipped so a mixed-recipe app can still boot.
                    "Skipping route {0}: recipe ''{1}'' is not supported yet",
                    definition.id(), definition.recipe());
        }
    }

    private void buildQueryJson(RouteBuilder builder, RouteFile routeFile) {
        pipelineThroughSql(builder, routeFile)
                .process(new JsonResponseRenderer(routeFile.definition().response().json()));
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

        Path sqlPath = routeFile.source().getParent().resolve(definition.sql().file()).normalize();
        String sqlUri = "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + DEFAULT_DATASOURCE
                + "&mode=" + definition.sql().effectiveMode()
                + "&resultKey=sql";

        ProcessorDefinition<?> route = builder.from(direct).routeId(routeId);
        applySecurity(route, definition.security());
        return route.process(new RequestBinder(definition)).to(sqlUri);
    }

    /** Inserts authenticate/authorize steps before binding when the route declares security. */
    private void applySecurity(ProcessorDefinition<?> route, SecuritySpec security) {
        if (security == null) {
            return;
        }
        if (security.auth() != null && !"public".equals(security.auth())) {
            route.to("tesseraql-auth:authenticate?auth=" + security.auth());
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
