package io.tesseraql.yaml.template;

import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
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

    /**
     * The context variable a render's message catalog rides in: read once per render, so a
     * page's twenty {@code #{key}} lookups list the {@code messages/} directory once, not twenty
     * times — and a Studio edit still lands on the very next render.
     */
    static final String CATALOG_VARIABLE = "__tesseraqlMessageCatalog";

    /** Renders with an explicit locale: {@code #{key}} lookups and {@code #locale} follow it. */
    public static String render(Path templateRoot, String templateName, Map<String, Object> model,
            java.util.Locale locale) {
        Path root = templateRoot.toAbsolutePath().normalize();
        try {
            return engineFor(root).process(templateName, context(root, locale, model));
        } catch (RuntimeException ex) {
            throw coded(ex);
        }
    }

    /**
     * Renders only the subtree a Thymeleaf markup selector picks (e.g. {@code #page-content})
     * — the shell-negotiation path (docs/view-composition.md wave 2a): the selected region
     * processes normally, while markup outside it (the page root's {@code th:replace} into the
     * shell included) never executes. A selector matching nothing renders the empty string —
     * callers fall back to the full render.
     */
    public static String render(Path templateRoot, String templateName, Map<String, Object> model,
            java.util.Locale locale, String selector) {
        Path root = templateRoot.toAbsolutePath().normalize();
        try {
            return engineFor(root).process(
                    new org.thymeleaf.TemplateSpec(templateName, java.util.Set.of(selector),
                            (TemplateMode) null, null),
                    context(root, locale, model));
        } catch (RuntimeException ex) {
            throw coded(ex);
        }
    }

    /**
     * A coded refusal raised while the template resolved a value keeps its code.
     *
     * <p>The engine wraps whatever a model object throws in its own processing exception, so
     * a refusal a request would otherwise answer with its number — a code catalog that has
     * never loaded, resolved on the read that asks for it (docs/lookups.md, decision 14 as
     * built) — would answer an uncoded 500 from a hand-written template and a coded one from
     * a declarative view. The engine's own failures (a template that is not there, a
     * malformed expression) carry no code and pass through as they are.
     */
    private static RuntimeException coded(RuntimeException ex) {
        for (Throwable cause = ex.getCause(); cause != null
                && cause != ex; cause = cause.getCause()) {
            if (cause instanceof io.tesseraql.core.error.TqlException refusal) {
                return refusal;
            }
        }
        return ex;
    }

    /**
     * The inline TEXT engine a notification's subject, an inbox title and body render through
     * (docs/notifications.md): a string template, the base-path link builder every engine
     * carries, and the app's message catalog — so {@code [(#{key})]} resolves in a subject or a
     * title as it does in the mail body, instead of rendering the {@code ??key_??} marker
     * (docs/audit-low-leads.md unfiled 54). The caller renders with {@link java.util.Locale#ENGLISH},
     * the locale every locale-less render reads (docs/internationalization.md).
     */
    public static TemplateEngine inlineEngine(Path appHome) {
        Path root = appHome.toAbsolutePath().normalize();
        org.thymeleaf.templateresolver.StringTemplateResolver resolver = new org.thymeleaf.templateresolver.StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        TemplateEngine engine = new TemplateEngine();
        engine.setLinkBuilder(new BasePathLinkBuilder());
        engine.setTemplateResolver(resolver);
        engine.setMessageResolver(new CatalogMessageResolver(root.resolve("messages"),
                I18nSettings.builtinCatalog()));
        return engine;
    }

    /** The render's context: the model, the locale, and the app catalog read once for it. */
    private static Context context(Path root, java.util.Locale locale, Map<String, Object> model) {
        Context context = new Context(locale, model);
        context.setVariable(CATALOG_VARIABLE, MessageCatalog.live(root.resolve("messages")));
        return context;
    }

    private static TemplateEngine engineFor(Path root) {
        return ENGINES.computeIfAbsent(root, key -> {
            // The framework-template override chain (docs/declarative-views.md, customization
            // ladder L2): an app shadows a framework template by dropping the same-named file
            // under its templates/ directory — templates/tql/view/form.html restyles every form,
            // templates/tql/email/hc-email.html swaps in a custom-themed mail fragment library
            // (docs/notifications.md). Checked ahead of the classpath resolver, falling through
            // when the app ships no override.
            FileTemplateResolver viewOverrides = new FileTemplateResolver();
            viewOverrides.setPrefix(key.resolve("templates") + java.io.File.separator);
            viewOverrides.setSuffix(".html");
            viewOverrides.setTemplateMode(TemplateMode.HTML);
            viewOverrides.setResolvablePatterns(java.util.Set.of("tql/view/*", "tql/email/*"));
            viewOverrides.setCharacterEncoding("UTF-8");
            viewOverrides.setCacheable(true);
            viewOverrides.setCheckExistence(true);
            viewOverrides.setOrder(0);

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
            shared.setOrder(1);

            FileTemplateResolver html = new FileTemplateResolver();
            html.setPrefix(key.toString() + java.io.File.separator);
            html.setTemplateMode(TemplateMode.HTML);
            html.setResolvablePatterns(java.util.Set.of("*.html"));
            html.setCharacterEncoding("UTF-8");
            html.setCacheable(true);
            html.setOrder(2);

            FileTemplateResolver text = new FileTemplateResolver();
            text.setPrefix(key.toString() + java.io.File.separator);
            text.setTemplateMode(TemplateMode.TEXT);
            text.setCharacterEncoding("UTF-8");
            text.setCacheable(true);
            text.setOrder(3);

            TemplateEngine engine = new TemplateEngine();
            engine.addTemplateResolver(viewOverrides);
            engine.addTemplateResolver(shared);
            engine.addTemplateResolver(html);
            engine.addTemplateResolver(text);
            // @{/x} resolves against the app's base path (docs/base-path.md); with none
            // configured it renders exactly what it says, so nothing changes for an app that
            // never asked for a prefix.
            engine.setLinkBuilder(new BasePathLinkBuilder());
            engine.setMessageResolver(new CatalogMessageResolver(
                    key.resolve("messages"), I18nSettings.builtinCatalog()));
            return engine;
        });
    }
}
