package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.yaml.model.ResponseSpec.TextResponse;
import io.tesseraql.yaml.template.Templates;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a template-generated text response (docs/prompt-as-recipe.md decision 3): the template
 * renders in Thymeleaf TEXT mode against the declared model, exactly as
 * {@link FileResponseRenderer} does, and the rendered string becomes the body.
 *
 * <p>This is the file renderer with the download headers removed, which is the whole difference
 * between a generated file and a generated message: a {@code prompts/get} answer has nowhere to
 * put a filename or a content type, so {@code text:} declares neither and nothing here sets one.
 * The status is still set, because the MCP endpoint reads it to tell a rendered message from a
 * failure the error renderer handled.
 */
public final class TextResponseRenderer implements Step {

    private final TextResponse response;
    private final Path appHome;
    private final String templateName;

    public TextResponseRenderer(TextResponse response, Path appHome, Path routeDir) {
        this.response = response;
        this.appHome = appHome.toAbsolutePath().normalize();
        this.templateName = TemplateResolution.resolve(
                this.appHome, routeDir, response.template());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Map<String, Object> model = new LinkedHashMap<>();
        response.model().forEach((key, expr) -> model.put(key,
                evaluation.resolve(Arrays.asList(String.valueOf(expr).split("\\.")))));

        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, response.effectiveStatus());
        exchange.getMessage().setBody(Templates.render(appHome, templateName, model));
    }
}
