package io.tesseraql.pdf;

import java.nio.file.Path;
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
 * never payload - and the locale is fixed to ROOT so rendering stays reproducible; data
 * formatting happens before the model reaches the template.
 */
final class PdfTemplates {

    /** The built-in plain-grid template used when an export declares no template. */
    static final String GRID = "tql-pdf/grid";

    private static final Map<Path, TemplateEngine> ENGINES = new ConcurrentHashMap<>();
    private static final TemplateEngine SHARED = engine(null);

    private PdfTemplates() {
    }

    /** Renders {@code templateName} (relative to {@code root}) against the model. */
    static String render(Path root, String templateName, Map<String, Object> model) {
        return ENGINES.computeIfAbsent(root.toAbsolutePath().normalize(), PdfTemplates::engine)
                .process(templateName, new Context(java.util.Locale.ROOT, model));
    }

    /** Renders the built-in grid template against the model. */
    static String renderGrid(Map<String, Object> model) {
        return SHARED.process(GRID, new Context(java.util.Locale.ROOT, model));
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
