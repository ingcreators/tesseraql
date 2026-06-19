package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTML renderer resolves {@code {expression}} placeholders in response-header values against the
 * execution context — so a header like {@code HX-Trigger} can carry per-request data — while a value
 * without a placeholder (a static header such as the CSP) is emitted unchanged.
 */
class HtmlResponseRendererTest {

    private static final EvaluationContext CTX = new EvaluationContext(Map.of(
            "result", Map.of("message", "Saved", "count", 3),
            "params", Map.of("id", 42)));

    @Test
    void interpolatesPlaceholdersInAStringHeaderValue() {
        assertThat(HtmlResponseRenderer.interpolate("/items/{params.id}", CTX))
                .isEqualTo("/items/42");
        // Mixed literal + placeholder.
        assertThat(HtmlResponseRenderer.interpolate("{result.count} done", CTX))
                .isEqualTo("3 done");
    }

    @Test
    void leavesAStaticHeaderUnchangedAndEmptiesUnknownBindings() {
        // A static header (e.g. the CSP) carries no placeholder, so it is emitted verbatim.
        assertThat(
                HtmlResponseRenderer.interpolate("default-src 'self'; frame-ancestors 'none'", CTX))
                .isEqualTo("default-src 'self'; frame-ancestors 'none'");
        // An unknown binding resolves to the empty string (like the redirect location).
        assertThat(HtmlResponseRenderer.interpolate("a{nope.missing}b", CTX)).isEqualTo("ab");
    }

    @Test
    void interpolatesRecursivelyIntoANestedHeaderMap() {
        // An HX-Trigger nested map: each leaf resolves, the keys and structure are preserved so the
        // renderer can serialize it to JSON ({"hc:toast":{"message":"Saved","variant":"success"}}).
        Map<String, Object> trigger = new LinkedHashMap<>();
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("message", "{result.message}");
        toast.put("variant", "success");
        trigger.put("hc:toast", toast);

        Object resolved = HtmlResponseRenderer.interpolate(trigger, CTX);

        assertThat(resolved).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) resolved;
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedToast = (Map<String, Object>) out.get("hc:toast");
        assertThat(resolvedToast).containsEntry("message", "Saved").containsEntry("variant",
                "success");
    }

    @Test
    void interpolatesLeafStringsInAListHeaderValue() {
        Object resolved = HtmlResponseRenderer.interpolate(List.of("{params.id}", "static"), CTX);
        assertThat(resolved).isEqualTo(List.of("42", "static"));
    }

    @Test
    void processEmitsAResolvedHxTriggerAndAnUnchangedStaticHeader(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("ok.html"), "<p th:text=\"'ok'\"></p>");
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("message", "{result.message}");
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("HX-Trigger", Map.of("hc:toast", toast));
        headers.put("Content-Security-Policy", "default-src 'self'");
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(
                new HtmlResponse(200, "ok.html", Map.of(), headers, null), dir, dir);

        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TesseraqlProperties.CONTEXT,
                Map.of("result", Map.of("message", "Saved")));
        renderer.process(exchange);

        // The placeholder resolves and the nested map serializes to JSON; the CSP is untouched.
        assertThat(exchange.getMessage().getHeader("HX-Trigger"))
                .isEqualTo("{\"hc:toast\":{\"message\":\"Saved\"}}");
        assertThat(exchange.getMessage().getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'self'");
    }

    @Test
    void aGuardedHeaderIsEmittedOnlyWhenItsConditionIsTruthy(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ok.html"), "<p th:text=\"'ok'\"></p>");
        Map<String, Object> headers = Map.of("HX-Trigger",
                Map.of("hc:toast", Map.of("message", "ok")));
        Map<String, String> guards = Map.of("HX-Trigger", "result.ok");
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(
                new HtmlResponse(200, "ok.html", Map.of(), headers, guards), dir, dir);

        // Condition true: the header is emitted.
        Exchange ok = exchange(dir, Map.of("result", Map.of("ok", true)), renderer);
        assertThat(ok.getMessage().getHeader("HX-Trigger")).isNotNull();

        // Condition false: the guarded header is skipped (e.g. a handled-error fragment).
        Exchange notOk = exchange(dir, Map.of("result", Map.of("ok", false)), renderer);
        assertThat(notOk.getMessage().getHeader("HX-Trigger")).isNull();
    }

    private static Exchange exchange(Path dir, Map<String, Object> context,
            HtmlResponseRenderer renderer) throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        renderer.process(exchange);
        return exchange;
    }
}
