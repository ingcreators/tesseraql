package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders an HTML page or fragment response from a Thymeleaf template and model (design ch. 6.4,
 * 12). The template path resolves like {@code sql.file}: first relative to the route's own
 * directory (the colocated yml + sql + html unit), falling back to the app's shared
 * {@code templates/} directory for cross-route fragments and layouts. Existence is verified at
 * build time (fail-fast); at request time the model expressions are resolved against the execution
 * context, the template is rendered, and configured response headers (such as {@code HX-Trigger})
 * are emitted, serializing nested values to JSON.
 */
public final class HtmlResponseRenderer implements Processor {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.TPL, 2001);

    private final HtmlResponse response;
    private final Path appHome;
    private final String templateName;
    private final String defaultLocaleTag;
    private final ObjectMapper mapper = new ObjectMapper();

    public HtmlResponseRenderer(HtmlResponse response, Path appHome, Path routeDir) {
        this(response, appHome, routeDir, "en");
    }

    public HtmlResponseRenderer(HtmlResponse response, Path appHome, Path routeDir,
            String defaultLocaleTag) {
        this.response = response;
        this.appHome = appHome.toAbsolutePath().normalize();
        this.templateName = resolveTemplate(this.appHome, routeDir, response.template());
        this.defaultLocaleTag = defaultLocaleTag;
    }

    /**
     * Resolves a route's template: colocated next to the route first, then the shared
     * {@code templates/} root; confined to the app home. Returns the app-home-relative name used
     * with the app's template engine.
     */
    static String resolveTemplate(Path appHome, Path routeDir, String template) {
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(template).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : appHome.resolve("templates").resolve(template).normalize();
        if (!file.startsWith(appHome)) {
            throw new TqlException(RENDER_ERROR, "Template escapes app home: " + template);
        }
        if (!Files.isRegularFile(file)) {
            throw new TqlException(RENDER_ERROR, "Template not found: " + template);
        }
        return appHome.relativize(file).toString().replace('\\', '/');
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Map<String, Object> model = new LinkedHashMap<>();
        response.model().forEach((key, expr) -> model.put(key,
                evaluation.resolve(Arrays.asList(String.valueOf(expr).split("\\.")))));

        // The negotiated request locale (roadmap Phase 22) drives #{key} lookups and #locale.
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE, defaultLocaleTag,
                String.class);
        String html = Templates.render(appHome, templateName, model,
                java.util.Locale.forLanguageTag(tag));

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
