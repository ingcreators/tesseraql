package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A path parameter is what the URL says.
 *
 * <p>The HTTP transport publishes path parameters as message headers — and query parameters and
 * form-body fields there too, under their own names. So the header for {@code {id}} on
 * {@code /users/{id}} was whatever wrote it last: a query parameter of that name arrived joined
 * with the path value, and a body field of that name replaced it outright. A route saying
 * {@code path.id} then bound an id the caller supplied, and so did every {@code params.id}
 * expression beside it.
 *
 * <p>Each test here fails against a binder that reads those headers.
 */
class RequestBinderPathParameterTest {

    private static RuntimeContext context;

    @BeforeAll
    static void start() throws Exception {
        context = new RuntimeContext();
        context.start();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static final RouteDefinition UNTYPED = route("""
            version: tesseraql/v1
            id: users.detail
            kind: route
            recipe: query-json
            response:
              json:
                body:
                  ok: "true"
            """);

    /** The same route with the path parameter also declared — typed path parameters. */
    private static final RouteDefinition TYPED = route("""
            version: tesseraql/v1
            id: users.detail
            kind: route
            recipe: query-json
            input:
              id:
                type: string
            response:
              json:
                body:
                  ok: "true"
            """);

    private static RouteDefinition route(String yaml) {
        return new SimpleYamlParser().parseRoute(yaml, "users.detail");
    }

    private static Exchange request(String uri, String body, Map<String, String> pathParams) {
        Exchange exchange = new Exchange(context.beans());
        if (uri != null) {
            exchange.request().uri(uri);
        }
        exchange.request().pathParams().putAll(pathParams);
        exchange.getMessage().setBody(body);
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> namespace(Exchange exchange, String key) {
        Map<String, Object> context = (Map<String, Object>) exchange
                .getProperty(io.tesseraql.pipeline.TesseraqlProperties.CONTEXT);
        return (Map<String, Object>) context.get(key);
    }

    @Test
    void aPathParameterComesFromTheUrl() {
        Exchange exchange = request("/users/u1", null, Map.of("id", "u1"));
        new RequestBinder(UNTYPED, "/users/{id}").process(exchange);

        assertThat(namespace(exchange, "path")).containsEntry("id", "u1");
    }

    /**
     * A query parameter of the same name lives in its own map now — the bag used to hand the
     * two values joined, so even {@code ?id=u1} on {@code /users/u1} missed.
     */
    @Test
    void aQueryParameterOfTheSameNameCannotDisplaceIt() {
        Exchange exchange = request("/users/u1?id=u2", null, Map.of("id", "u1"));
        exchange.request().queryParams().put("id", java.util.List.of("u2"));
        new RequestBinder(UNTYPED, "/users/{id}").process(exchange);

        assertThat(namespace(exchange, "path")).containsEntry("id", "u1");
    }

    /**
     * A declared input sharing a path parameter's name <em>types</em> that path parameter
     * (roadmap Phase 40); it does not source it, so a body field of that name is not an
     * alternative value for it — in {@code path.*} or in {@code params.*}.
     */
    @Test
    void aBodyFieldOfTheSameNameCannotDisplaceIt() {
        Exchange exchange = request("/users/u1", "{\"id\":\"u2\"}", Map.of("id", "u1"));
        new RequestBinder(TYPED, "/users/{id}").process(exchange);

        assertThat(namespace(exchange, "path")).containsEntry("id", "u1");
        assertThat(namespace(exchange, "params")).containsEntry("id", "u1");
    }

    /**
     * With no URL of its own — a programmatic invocation of a mounted route — the caller's
     * path parameters are the path parameters: the value's owner is the map, not a URI string
     * this test would have to fabricate.
     */
    @Test
    void withoutARequestUrlTheCallersPathParamsStillAnswer() {
        Exchange exchange = request(null, null, Map.of("id", "u1"));
        new RequestBinder(UNTYPED, "/users/{id}").process(exchange);

        assertThat(namespace(exchange, "path")).containsEntry("id", "u1");
    }
}
