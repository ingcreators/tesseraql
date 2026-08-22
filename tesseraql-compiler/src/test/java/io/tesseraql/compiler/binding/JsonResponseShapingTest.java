package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Response shaping (roadmap Phase 41): computed leaves, nest:, and statusWhen:. */
class JsonResponseShapingTest {

    private static Exchange render(ResponseSpec.JsonResponse response,
            Map<String, Object> context) throws Exception {
        Exchange exchange = new Exchange(
                Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        new JsonResponseRenderer(response).process(exchange);
        return exchange;
    }

    @Test
    void bodyLeavesAreCoreLanguageExpressions() throws Exception {
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200, Map.of(
                "total", "params.qty * params.price",
                "label", "upper(params.name)",
                "rows", "main.rows"), null, null);
        Exchange exchange = render(response, Map.of(
                "params", Map.of("qty", 3, "price", 4, "name", "sato"),
                "main", Map.of("rows", List.of(Map.of("id", 1)))));
        String json = exchange.getBody(String.class);
        assertThat(json).contains("\"total\":12").contains("\"label\":\"SATO\"")
                .contains("\"rows\":[{\"id\":1}]");
    }

    @Test
    void aLegacyUnparsableLeafStillResolvesAsADottedPath() throws Exception {
        // "steps.record.keys.id"-style paths parse as expressions; something the grammar
        // rejects falls back to the legacy resolver (here: resolves to null, as before).
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200, Map.of(
                "odd", "not an # expression"), null, null);
        Exchange exchange = render(response, Map.of());
        assertThat(exchange.getBody(String.class)).contains("\"odd\":null");
    }

    @Test
    void statusWhenMapsBusinessConditionsToStatuses() throws Exception {
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200,
                Map.of("data", "main.rows"), null,
                List.of(new ResponseSpec.StatusWhen("main.rowCount == 0", 404)));
        Exchange missing = render(response, Map.of("main", Map.of("rows", List.of(),
                "rowCount", 0)));
        assertThat(missing.response().status()).isEqualTo(404);
        Exchange found = render(response, Map.of("main", Map.of("rows",
                List.of(Map.of("id", 1)), "rowCount", 1)));
        assertThat(found.response().status()).isEqualTo(200);
    }
}
