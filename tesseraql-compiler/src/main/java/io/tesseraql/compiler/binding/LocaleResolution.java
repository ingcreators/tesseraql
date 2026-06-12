package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import java.util.List;
import java.util.Locale;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Resolves the request locale (roadmap Phase 22): user preference, then {@code Accept-Language}
 * negotiation, then the app default — published as {@link TesseraqlProperties#LOCALE} for
 * downstream steps (input parsing, template rendering, error rendering, export formatting).
 *
 * <p>A preference source is a {@code principal.*} path (e.g. {@code principal.claim.locale}) or a
 * {@code query.<name>} request parameter. Every candidate must negotiate against the supported
 * locales (RFC 4647 lookup, so {@code ja-JP} matches a supported {@code ja}); an unsupported
 * preference falls through to the next source rather than serving an untranslated locale.
 */
public final class LocaleResolution implements Processor {

    private final I18nSettings settings;

    public LocaleResolution(I18nSettings settings) {
        this.settings = settings;
    }

    @Override
    public void process(Exchange exchange) {
        exchange.setProperty(TesseraqlProperties.LOCALE, resolve(exchange));
    }

    private String resolve(Exchange exchange) {
        for (String source : settings.preferenceSources()) {
            String tag = negotiate(preference(exchange, source));
            if (tag != null) {
                return tag;
            }
        }
        String header = exchange.getMessage().getHeader("Accept-Language", String.class);
        String negotiated = negotiate(header);
        return negotiated != null ? negotiated : settings.defaultTag();
    }

    /** Reads one preference source; the context map is not built yet, so query reads headers. */
    private static String preference(Exchange exchange, String source) {
        if (source.startsWith("query.")) {
            // platform-http exposes query parameters as message headers.
            return exchange.getMessage().getHeader(
                    source.substring("query.".length()), String.class);
        }
        return FormatSources.resolve(null,
                exchange.getProperty(TesseraqlProperties.PRINCIPAL), source);
    }

    /** RFC 4647 lookup of the candidate ranges against the supported locales; null if no match. */
    private String negotiate(String ranges) {
        if (ranges == null || ranges.isBlank()) {
            return null;
        }
        List<Locale.LanguageRange> parsed;
        try {
            parsed = Locale.LanguageRange.parse(ranges);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String tag = Locale.lookupTag(parsed, settings.supportedTags());
        return tag == null ? null : Locale.forLanguageTag(tag).toLanguageTag();
    }
}
