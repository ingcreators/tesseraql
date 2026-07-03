package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

/** Response shaping (roadmap Phase 41): computed leaves, nest:, and statusWhen:. */
class JsonResponseShapingTest {

    private static Exchange render(ResponseSpec.JsonResponse response,
            Map<String, Object> context) throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        new JsonResponseRenderer(response).process(exchange);
        return exchange;
    }

    @Test
    void bodyLeavesAreCoreLanguageExpressions() throws Exception {
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200, Map.of(
                "total", "params.qty * params.price",
                "label", "upper(params.name)",
                "rows", "sql.rows"), null, null, null);
        Exchange exchange = render(response, Map.of(
                "params", Map.of("qty", 3, "price", 4, "name", "sato"),
                "sql", Map.of("rows", List.of(Map.of("id", 1)))));
        String json = exchange.getMessage().getBody(String.class);
        assertThat(json).contains("\"total\":12").contains("\"label\":\"SATO\"")
                .contains("\"rows\":[{\"id\":1}]");
    }

    @Test
    void aLegacyUnparsableLeafStillResolvesAsADottedPath() throws Exception {
        // "steps.record.keys.id"-style paths parse as expressions; something the grammar
        // rejects falls back to the legacy resolver (here: resolves to null, as before).
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200, Map.of(
                "odd", "not an # expression"), null, null, null);
        Exchange exchange = render(response, Map.of());
        assertThat(exchange.getMessage().getBody(String.class)).contains("\"odd\":null");
    }

    @Test
    void nestGroupsChildRowsUnderTheirParents() throws Exception {
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200,
                Map.of("orders", "sql.rows"), null, null,
                List.of(new ResponseSpec.NestSpec("orders", "lines", "lines",
                        Map.of("id", "order_id"))));
        Exchange exchange = render(response, Map.of(
                "sql", Map.of("rows", List.of(row("id", 1), row("id", 2))),
                "lines", Map.of("rows", List.of(
                        row("order_id", 1, "sku", "A"),
                        row("order_id", 1, "sku", "B"),
                        row("order_id", 2, "sku", "C")))));
        String json = exchange.getMessage().getBody(String.class);
        assertThat(json).contains(
                "{\"id\":1,\"lines\":[{\"order_id\":1,\"sku\":\"A\"},{\"order_id\":1,\"sku\":\"B\"}]}");
        assertThat(json).contains("{\"id\":2,\"lines\":[{\"order_id\":2,\"sku\":\"C\"}]}");
    }

    /** An insertion-ordered row (Map.of iteration order is randomized). */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }

    @Test
    void statusWhenMapsBusinessConditionsToStatuses() throws Exception {
        ResponseSpec.JsonResponse response = new ResponseSpec.JsonResponse(200,
                Map.of("data", "sql.rows"), null,
                List.of(new ResponseSpec.StatusWhen("sql.rowCount == 0", 404)), null);
        Exchange missing = render(response, Map.of("sql", Map.of("rows", List.of(),
                "rowCount", 0)));
        assertThat(missing.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(404);
        Exchange found = render(response, Map.of("sql", Map.of("rows",
                List.of(Map.of("id", 1)), "rowCount", 1)));
        assertThat(found.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(200);
    }
}
