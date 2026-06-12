package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.i18n.MessageCatalog;
import java.text.MessageFormat;
import java.util.Locale;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.messageresolver.IMessageResolver;

/**
 * Resolves Thymeleaf {@code #{key}} message expressions against the app's message catalog
 * (roadmap Phase 22): the app's {@code messages/<locale>.yml} files layered over the framework
 * built-ins, looked up with the rendering context's locale (exact tag, then bare language).
 *
 * <p>A missing key renders as the standard {@code ??key_locale??} marker, so translation gaps
 * stay visible in the page (and the i18n lint reports them at build time).
 */
final class CatalogMessageResolver implements IMessageResolver {

    private final MessageCatalog catalog;

    CatalogMessageResolver(MessageCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getName() {
        return "TESSERAQL-CATALOG";
    }

    @Override
    public Integer getOrder() {
        return 0;
    }

    @Override
    public String resolveMessage(ITemplateContext context, Class<?> origin, String key,
            Object[] messageParameters) {
        Locale locale = context == null ? Locale.ENGLISH : context.getLocale();
        String tag = locale == null || locale.getLanguage().isEmpty()
                ? "en"
                : locale.toLanguageTag();
        String message = catalog.resolve(tag, key);
        if (message == null) {
            return null;
        }
        // Positional parameters (#{key(${x})}) format like Thymeleaf's standard resolver;
        // parameterless lookups return the template text as written, so the hc-style {name}
        // placeholders survive for client-side interpolation.
        if (messageParameters != null && messageParameters.length > 0) {
            return new MessageFormat(message, locale).format(messageParameters);
        }
        return message;
    }

    @Override
    public String createAbsentMessageRepresentation(ITemplateContext context, Class<?> origin,
            String key, Object[] messageParameters) {
        Locale locale = context == null ? Locale.ENGLISH : context.getLocale();
        return "??" + key + "_" + locale + "??";
    }
}
