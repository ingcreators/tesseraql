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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** A {@code {expression}} placeholder in a header value, resolved like the redirect location. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

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

        // Publish the browser session's CSRF token (stashed on authentication) as the reserved
        // model variable `_csrf`, so the shell can emit <meta name="csrf-token"> for the
        // Hypermedia Components installCsrfHeader convention and forms can carry a hidden field.
        String csrfToken = exchange.getProperty(TesseraqlProperties.CSRF_TOKEN, String.class);
        if (csrfToken != null) {
            model.put("_csrf", csrfToken);
        }

        // The negotiated request locale (roadmap Phase 22) drives #{key} lookups and #locale.
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE, defaultLocaleTag,
                String.class);
        String html = Templates.render(appHome, templateName, model,
                java.util.Locale.forLanguageTag(tag));

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, response.effectiveStatus());
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
        applyHeaders(exchange, evaluation);
        exchange.getMessage().setBody(html);
    }

    private void applyHeaders(Exchange exchange, EvaluationContext evaluation) {
        response.headers().forEach((name, value) -> {
            try {
                // Resolve {expression} placeholders against the execution context (recursively for a
                // nested map/list), so a header like HX-Trigger can carry per-request data; a value
                // with no placeholder is unchanged. Nested map/list values then serialize to JSON.
                Object resolved = interpolate(value, evaluation);
                String headerValue = resolved instanceof Map || resolved instanceof List
                        ? mapper.writeValueAsString(resolved)
                        : String.valueOf(resolved);
                exchange.getMessage().setHeader(name, headerValue);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new TqlException(RENDER_ERROR, "Failed to serialize header " + name);
            }
        });
    }

    /** Resolves {@code {expression}} placeholders in a header value (recursively into maps/lists). */
    @SuppressWarnings("unchecked")
    static Object interpolate(Object value, EvaluationContext evaluation) {
        if (value instanceof String string) {
            return interpolateString(string, evaluation);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, interpolate(v, evaluation)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(element -> interpolate(element, evaluation)).toList();
        }
        return value;
    }

    private static String interpolateString(String template, EvaluationContext evaluation) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object resolved = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
