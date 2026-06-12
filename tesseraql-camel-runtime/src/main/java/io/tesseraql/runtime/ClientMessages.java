package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the client-side message catalog module served at
 * {@code /assets/_tesseraql/messages.js?locale=<tag>} (roadmap Phase 22): the app's
 * {@code messages/<locale>.yml} entries layered over the framework's Japanese translations of
 * the Hypermedia Components built-in strings, emitted as a {@code setMessages()} call.
 *
 * <p>The module imports {@code setMessages} from the behaviors bundle itself — the bundle
 * inlines the kit's i18n state, so only its own export mutates the catalog the behaviors read.
 * The shell loads this module before the framework bootstrap; module scripts execute in document
 * order, so the catalog is merged before the behaviors install at {@code DOMContentLoaded}.
 */
final class ClientMessages {

    private static final String BEHAVIORS_MODULE = "/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js";

    private final MessageCatalog catalog;
    private final String defaultTag;
    private final ObjectMapper mapper = new ObjectMapper();

    ClientMessages(Path appHome, String defaultTag) {
        this.catalog = MessageCatalog.load(appHome.resolve("messages")).withFallback(kitCatalog());
        this.defaultTag = defaultTag;
    }

    /** The catalog module for the requested locale; a missing/invalid tag serves the default. */
    byte[] script(String localeParam) {
        String tag = normalize(localeParam);
        Map<String, String> entries = catalog.forLocale(tag);
        try {
            return ("import { setMessages } from \"" + BEHAVIORS_MODULE + "\";\n"
                    + "setMessages(" + mapper.writeValueAsString(entries) + ");\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize message catalog", ex);
        }
    }

    /** Normalizes the locale query parameter; invalid or absent tags fall to the app default. */
    String normalize(String localeParam) {
        if (localeParam == null || localeParam.isBlank()) {
            return defaultTag;
        }
        Locale locale = Locale.forLanguageTag(localeParam.trim());
        return locale.getLanguage().isEmpty() ? defaultTag : locale.toLanguageTag();
    }

    /** The framework's translations of the kit's built-in strings (Japanese so far). */
    private static MessageCatalog kitCatalog() {
        String resource = "tesseraql/messages/hc/ja.yml";
        try (InputStream in = ClientMessages.class.getClassLoader()
                .getResourceAsStream(resource)) {
            return in == null
                    ? MessageCatalog.empty()
                    : MessageCatalog.parse("ja", in, "classpath:" + resource);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
