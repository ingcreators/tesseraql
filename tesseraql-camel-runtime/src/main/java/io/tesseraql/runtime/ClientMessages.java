package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the client-side message catalog module served at
 * {@code /assets/_tesseraql/messages.js?locale=<tag>} (roadmap Phase 22): the kit's official
 * locale pack (when one ships for the locale's language, hc 0.1.1+) followed by the app's
 * {@code messages/<locale>.yml} entries — later {@code setMessages()} merges win, so app
 * wording layers over the pack.
 *
 * <p>The module imports {@code setMessages} from the behaviors bundle; since hc 0.1.1 the
 * catalog is a {@code globalThis}-keyed singleton shared across dist bundle copies, and the
 * locale packs are plain data modules under {@code dist/locales/}. The shell loads this module
 * before the framework bootstrap; module scripts execute in document order, so the catalog is
 * merged before the behaviors install at {@code DOMContentLoaded}.
 */
final class ClientMessages {

    private static final String VENDOR_BASE = "/assets/vendor/hypermedia-components__core/dist/";
    private static final String WEBJAR = "hypermedia-components__core";
    private static final String WEBJAR_RESOURCES = "META-INF/resources/webjars/";

    private final Path messagesDir;
    private final String defaultTag;
    private final ObjectMapper mapper = new ObjectMapper();
    private final org.webjars.WebJarVersionLocator webJars = new org.webjars.WebJarVersionLocator();

    ClientMessages(Path appHome, String defaultTag) {
        this.messagesDir = appHome.resolve("messages");
        this.defaultTag = defaultTag;
    }

    /** The catalog module for the requested locale; a missing/invalid tag serves the default. */
    byte[] script(String localeParam) {
        String tag = normalize(localeParam);
        String language = new Locale.Builder().setLanguageTag(tag).build().getLanguage();
        // Read the catalog live so a Studio message edit is served on the next request (no restart).
        Map<String, String> entries = MessageCatalog.live(messagesDir).forLocale(tag);
        StringBuilder script = new StringBuilder();
        script.append("import { setMessages } from \"")
                .append(VENDOR_BASE).append("hc.behaviors.min.js\";\n");
        if (kitPackExists(language)) {
            script.append("import pack from \"")
                    .append(VENDOR_BASE).append("locales/").append(language).append(".js\";\n")
                    .append("setMessages(pack);\n");
        }
        try {
            script.append("setMessages(").append(mapper.writeValueAsString(entries))
                    .append(");\n");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize message catalog", ex);
        }
        return script.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Normalizes the locale query parameter; invalid or absent tags fall to the app default. */
    String normalize(String localeParam) {
        if (localeParam == null || localeParam.isBlank()) {
            return defaultTag;
        }
        Locale locale = Locale.forLanguageTag(localeParam.trim());
        return locale.getLanguage().isEmpty() ? defaultTag : locale.toLanguageTag();
    }

    /** Whether the kit ships a locale pack for the language (English is its built-in default). */
    private boolean kitPackExists(String language) {
        if (language.isEmpty()) {
            return false;
        }
        String version = webJars.version(WEBJAR);
        return version != null && getClass().getClassLoader().getResource(
                WEBJAR_RESOURCES + WEBJAR + "/" + version + "/dist/locales/" + language
                        + ".js") != null;
    }
}
