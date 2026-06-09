package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.template.HtmlTemplateEngine;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders an HTML fragment response from a template and model (design ch. 6.4, 12, the
 * {@code tesseraql-html:render} step).
 *
 * <p>The template is compiled once at build time. At request time the model expressions are
 * resolved against the execution context, the fragment is rendered, and configured response
 * headers (such as {@code HX-Trigger}) are emitted, serializing nested values to JSON.
 */
public final class HtmlResponseRenderer implements Processor {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.TPL, 2001);

    private final HtmlResponse response;
    private final HtmlTemplateEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public HtmlResponseRenderer(HtmlResponse response, Path templateRoot) {
        this.response = response;
        Path templateFile = templateRoot.resolve(response.template()).normalize();
        if (!templateFile.startsWith(templateRoot)) {
            throw new TqlException(RENDER_ERROR, "Template escapes template root: " + response.template());
        }
        try {
            this.engine = HtmlTemplateEngine.compile(Files.readString(templateFile));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Map<String, Object> model = new LinkedHashMap<>();
        response.model().forEach((key, expr) ->
                model.put(key, evaluation.resolve(Arrays.asList(String.valueOf(expr).split("\\.")))));

        String html = engine.render(model);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, response.effectiveStatus());
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
        applyHeaders(exchange);
        exchange.getMessage().setBody(html);
    }

    private void applyHeaders(Exchange exchange) {
        response.headers().forEach((name, value) -> {
            try {
                String headerValue = value instanceof Map || value instanceof java.util.List
                        ? mapper.writeValueAsString(value)
                        : String.valueOf(value);
                exchange.getMessage().setHeader(name, headerValue);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new TqlException(RENDER_ERROR, "Failed to serialize header " + name);
            }
        });
    }
}
