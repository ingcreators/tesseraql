package io.tesseraql.pdf;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.function.Function;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.messageresolver.IMessageResolver;

/**
 * Resolves a print template's {@code #{key}} expressions against the messages the export
 * carries (docs/printable-documents.md): the application's {@code messages/} catalogs over the
 * framework's built-ins, already narrowed to the locale the document renders in. The codec
 * publishes that lookup to the render context under {@link #VARIABLE}; a context without one —
 * a caller that built its write spec by hand — resolves nothing, and the key renders as the
 * standard {@code ??key_locale??} marker, the same marker a page shows for a translation gap.
 *
 * <p>Before this resolver the engine had Thymeleaf's default one and no catalog behind it, so
 * every message expression in every print template rendered as that marker while the page
 * promised the opposite.
 */
final class DocumentMessageResolver implements IMessageResolver {

    /** The context variable carrying the document's {@code key -> text} lookup. */
    static final String VARIABLE = "tql.documentMessages";

    @Override
    public String getName() {
        return "TESSERAQL-DOCUMENT";
    }

    @Override
    public Integer getOrder() {
        return 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String resolveMessage(ITemplateContext context, Class<?> origin, String key,
            Object[] messageParameters) {
        Object lookup = context == null ? null : context.getVariable(VARIABLE);
        if (!(lookup instanceof Function<?, ?> messages)) {
            return null;
        }
        String message = ((Function<String, String>) messages).apply(key);
        if (message == null) {
            return null;
        }
        // Positional parameters (#{key(${x})}) format like Thymeleaf's standard resolver; a
        // parameterless lookup returns the catalog text as written.
        if (messageParameters != null && messageParameters.length > 0) {
            Locale locale = context.getLocale() == null ? Locale.ENGLISH : context.getLocale();
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
