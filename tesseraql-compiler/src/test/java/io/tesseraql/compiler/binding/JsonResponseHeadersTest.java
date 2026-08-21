package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.model.ResponseSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code response.json.headers:} and its per-header guards.
 *
 * <p>The block existed on {@code response.html} only. On a JSON route it deserialized away in
 * silence at runtime — the linter reported it as an unknown key (TQL-YAML-1043), so an author who
 * ran the linter was told, and one who did not saw a header they had declared simply not arrive.
 *
 * <p>The guards are here for one case in particular: a JSON response can already vary its status
 * per request through {@code statusWhen}, so the headers <em>defined in terms of that status</em> —
 * {@code Location} on a 201, {@code Retry-After} on a 503, {@code WWW-Authenticate} on a 401 — have
 * to be able to vary with it. Anything else conditional belongs in the body, which a JSON client
 * parses anyway.
 */
class JsonResponseHeadersTest {

    private static Exchange render(ResponseSpec.JsonResponse response,
            Map<String, Object> context) throws Exception {
        Exchange exchange = new Exchange(
                new RuntimeContext().beans());
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        new JsonResponseRenderer(response).process(exchange);
        return exchange;
    }

    private static ResponseSpec.JsonResponse response(Map<String, Object> headers,
            Map<String, String> headersWhen, List<ResponseSpec.StatusWhen> statusWhen) {
        return new ResponseSpec.JsonResponse(200, Map.of("ok", "true"), null, statusWhen,
                headers, headersWhen);
    }

    @Test
    void aDeclaredHeaderIsEmitted() throws Exception {
        Exchange exchange = render(
                response(Map.of("Cache-Control", "no-store"), null, null), Map.of());

        assertThat(exchange.getMessage().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void placeholdersResolveAgainstTheRequest() throws Exception {
        Exchange exchange = render(
                response(Map.of("Location", "/api/items/{steps.record.keys.id}"), null, null),
                Map.of("steps", Map.of("record", Map.of("keys", Map.of("id", 42)))));

        assertThat(exchange.getMessage().getHeader("Location")).isEqualTo("/api/items/42");
    }

    /**
     * The case the guards exist for: one route answers 201 when it created a row and 200 when it
     * did not, and {@code Location} is only meaningful on the 201.
     */
    @Test
    void aStatusCoupledHeaderFollowsTheStatusItDescribes() throws Exception {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Location", "/api/items/{steps.record.keys.id}");
        Map<String, String> guards = Map.of("Location", "steps.record.created");
        List<ResponseSpec.StatusWhen> statusWhen = List
                .of(new ResponseSpec.StatusWhen("steps.record.created", 201));

        Exchange created = render(response(headers, guards, statusWhen),
                Map.of("steps", Map.of("record",
                        Map.of("created", true, "keys", Map.of("id", 42)))));
        assertThat(created.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE)).isEqualTo(201);
        assertThat(created.getMessage().getHeader("Location")).isEqualTo("/api/items/42");

        Exchange unchanged = render(response(headers, guards, statusWhen),
                Map.of("steps", Map.of("record",
                        Map.of("created", false, "keys", Map.of("id", 42)))));
        assertThat(unchanged.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE)).isEqualTo(200);
        assertThat(unchanged.getMessage().getHeader("Location"))
                .as("a 200 that created nothing has nowhere to point")
                .isNull();
    }

    /** The renderer owns the content type; a declared header cannot describe another body. */
    @Test
    void theRenderersOwnContentTypeWins() throws Exception {
        Exchange exchange = render(
                response(Map.of("Content-Type", "text/csv"), null, null), Map.of());

        assertThat(exchange.getMessage().getHeader(Headers.CONTENT_TYPE))
                .isEqualTo("application/json; charset=utf-8");
    }
}
