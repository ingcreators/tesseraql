package io.tesseraql.pdf;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * Print-template rendering (roadmap Phase 21): the standard template engine (design ch. 12) in
 * HTML mode, one engine per app resource root, with the framework's built-in grid template
 * resolving from the classpath. Templates are app-authored files confined to the app home -
 * never payload. The template renders in the export's locale — declared, request-sourced or
 * configured — and in English when the export declares none (docs/export-hygiene.md P6): the
 * same definition of a locale-less render every other template surface uses, and never the
 * JVM's default, so a generated file stays reproducible across hosts. A context in
 * {@code Locale.ROOT} used to put two locales in one document and made every message expression
 * throw after the query had run.
 */
final class PdfTemplates {

    /** The built-in plain-grid template used when an export declares no template. */
    static final String GRID = "tql-pdf/grid";

    private static final Map<Path, TemplateEngine> ENGINES = new ConcurrentHashMap<>();
    private static final TemplateEngine SHARED = engine(null);

    private PdfTemplates() {
    }

    /** Renders {@code templateName} (relative to {@code root}) against the model. */
    static String render(Path root, String templateName, Map<String, Object> model,
            Locale locale) {
        return ENGINES.computeIfAbsent(root.toAbsolutePath().normalize(), PdfTemplates::engine)
                .process(templateName, new Context(locale, model));
    }

    /** Renders the built-in grid template against the model. */
    static String renderGrid(Map<String, Object> model, Locale locale) {
        return SHARED.process(GRID, new Context(locale, model));
    }

    /**
     * The locale a template renders in: the export's own when it declares one, English otherwise.
     * A tag with no language ({@code und} — what a negotiated {@code request.locale} becomes when
     * the i18n default folds) is "none" too: as {@code Locale.ROOT} it would bring the
     * message-lookup failure back.
     */
    static Locale templateLocale(String declared) {
        if (declared == null || declared.isBlank()) {
            return Locale.ENGLISH;
        }
        Locale locale = Locale.forLanguageTag(declared);
        return locale.getLanguage().isEmpty() ? Locale.ENGLISH : locale;
    }

    private static TemplateEngine engine(Path root) {
        ClassLoaderTemplateResolver shared = new ClassLoaderTemplateResolver(
                PdfTemplates.class.getClassLoader());
        shared.setPrefix("tesseraql/pdf/");
        shared.setSuffix(".html");
        shared.setTemplateMode(TemplateMode.HTML);
        shared.setResolvablePatterns(java.util.Set.of("tql-pdf/*"));
        shared.setCharacterEncoding("UTF-8");
        shared.setCacheable(true);
        shared.setOrder(0);

        TemplateEngine engine = new TemplateEngine();
        engine.addTemplateResolver(shared);
        if (root != null) {
            FileTemplateResolver files = new FileTemplateResolver();
            files.setPrefix(root.toString() + java.io.File.separator);
            files.setTemplateMode(TemplateMode.HTML);
            files.setCharacterEncoding("UTF-8");
            files.setCacheable(true);
            files.setOrder(1);
            engine.addTemplateResolver(files);
        }
        return engine;
    }
}
