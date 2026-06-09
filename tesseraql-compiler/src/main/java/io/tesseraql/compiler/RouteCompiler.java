package io.tesseraql.compiler;

import io.tesseraql.compiler.binding.JsonResponseRenderer;
import io.tesseraql.compiler.binding.RequestBinder;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import org.apache.camel.builder.RouteBuilder;
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
                for (RouteFile routeFile : manifest.routes()) {
                    buildRoute(this, manifest.appHome(), routeFile);
                }
            }
        };
    }

    private void buildRoute(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String recipe = definition.recipe();
        if (!"query-json".equals(recipe)) {
            // Only query-json is implemented in the first milestone; other recipes are skipped
            // with a warning so an app that mixes recipes can still boot.
            LOG.log(System.Logger.Level.WARNING,
                    "Skipping route {0}: recipe ''{1}'' is not supported yet",
                    definition.id(), recipe);
            return;
        }
        buildQueryJson(builder, appHome, routeFile);
    }

    private void buildQueryJson(RouteBuilder builder, Path appHome, RouteFile routeFile) {
        RouteDefinition definition = routeFile.definition();
        String routeId = definition.id();
        String direct = "direct:" + routeId;

        restEndpoint(builder, routeFile.httpMethod(), routeFile.urlPath()).to(direct);

        Path sqlPath = routeFile.source().getParent().resolve(definition.sql().file()).normalize();
        String sqlUri = "tesseraql-sql:file:" + sqlPath
                + "?datasource=" + DEFAULT_DATASOURCE
                + "&mode=" + definition.sql().effectiveMode()
                + "&resultKey=sql";

        builder.from(direct)
                .routeId(routeId)
                .process(new RequestBinder(definition))
                .to(sqlUri)
                .process(new JsonResponseRenderer(definition.response().json()));
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
