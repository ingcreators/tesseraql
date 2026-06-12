package io.tesseraql.compiler.binding;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * The standard template engine (design ch. 12): Thymeleaf with one engine per app template root.
 * {@code *.html} templates render in HTML mode (natural templates, escaped by default, fragments
 * via {@code th:fragment}/{@code th:insert}); every other extension renders in TEXT mode for
 * generated file responses ({@code [(${value})]} interpolation, {@code [# th:if]} blocks).
 *
 * <p>{@code #{key}} message expressions resolve against the root's {@code messages/<locale>.yml}
 * catalogs layered over the framework built-ins (roadmap Phase 22), looked up with the rendering
 * locale — page renders pass the negotiated request locale; locale-less renders (mail bodies,
 * generated file responses) read the English/default texts.
 */
public final class Templates {

    private static final Map<Path, TemplateEngine> ENGINES = new ConcurrentHashMap<>();

    private Templates() {
    }

    /** Renders {@code templateName} (relative to {@code templateRoot}) against the model. */
    public static String render(Path templateRoot, String templateName, Map<String, Object> model) {
        return render(templateRoot, templateName, model, java.util.Locale.ENGLISH);
    }

    /** Renders with an explicit locale: {@code #{key}} lookups and {@code #locale} follow it. */
    public static String render(Path templateRoot, String templateName, Map<String, Object> model,
            java.util.Locale locale) {
        Context context = new Context(locale, model);
        return engineFor(templateRoot.toAbsolutePath().normalize()).process(templateName, context);
    }

    private static TemplateEngine engineFor(Path root) {
        return ENGINES.computeIfAbsent(root, key -> {
            // Framework-shared fragments (the tql/* namespace, e.g. the hc-shell page layout)
            // resolve from the classpath so every app can th:replace them without copying.
            org.thymeleaf.templateresolver.ClassLoaderTemplateResolver shared = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver(
                    Templates.class.getClassLoader());
            shared.setPrefix("tesseraql/templates/");
            shared.setSuffix(".html");
            shared.setTemplateMode(TemplateMode.HTML);
            shared.setResolvablePatterns(java.util.Set.of("tql/*"));
            shared.setCharacterEncoding("UTF-8");
            shared.setCacheable(true);
            shared.setOrder(0);

            FileTemplateResolver html = new FileTemplateResolver();
            html.setPrefix(key.toString() + java.io.File.separator);
            html.setTemplateMode(TemplateMode.HTML);
            html.setResolvablePatterns(java.util.Set.of("*.html"));
            html.setCharacterEncoding("UTF-8");
            html.setCacheable(true);
            html.setOrder(1);

            FileTemplateResolver text = new FileTemplateResolver();
            text.setPrefix(key.toString() + java.io.File.separator);
            text.setTemplateMode(TemplateMode.TEXT);
            text.setCharacterEncoding("UTF-8");
            text.setCacheable(true);
            text.setOrder(2);

            TemplateEngine engine = new TemplateEngine();
            engine.addTemplateResolver(shared);
            engine.addTemplateResolver(html);
            engine.addTemplateResolver(text);
            engine.setMessageResolver(new CatalogMessageResolver(
                    io.tesseraql.yaml.i18n.MessageCatalog.load(key.resolve("messages"))
                            .withFallback(I18nSettings.builtinCatalog())));
            return engine;
        });
    }
}
