package io.tesseraql.compiler.binding;

import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec.FileResponse;
import io.tesseraql.yaml.template.Templates;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a template-generated file response (design ch. 6.4): non-HTML templates render in
 * Thymeleaf TEXT mode ({@code [(${value})]} interpolation, {@code [# th:if]} blocks); the text is
 * served with the configured content type, as an attachment download when a filename is set. This
 * is the general text-generation primitive for business apps (config files, fixed-format exports,
 * receipts) alongside the SQL-driven CSV export.
 */
public final class FileResponseRenderer implements Step {

    private final FileResponse response;
    private final Path appHome;
    private final String templateName;

    public FileResponseRenderer(FileResponse response, Path appHome, Path routeDir) {
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
        exchange.getMessage().setHeader(Headers.CONTENT_TYPE, response.effectiveContentType());
        if (response.filename() != null && !response.filename().isBlank()) {
            exchange.getMessage().setHeader("Content-Disposition",
                    "attachment; filename=\"" + sanitizeFilename(response.filename()) + "\"");
        }
        exchange.getMessage().setBody(Templates.render(appHome, templateName, model));
    }

    /** Keeps the download filename header-safe (no quotes or control characters). */
    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[\\\\\"\\r\\n]", "_");
    }
}
