package io.tesseraql.yaml.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Per-app message catalogs (roadmap Phase 22): one {@code messages/<locale>.yml} file per BCP-47
 * language tag under the app home, nested YAML maps flattened to dotted keys
 * ({@code users.provision.unknown-user}).
 *
 * <p>Catalogs layer: an app catalog typically composes {@link #withFallback(MessageCatalog) over}
 * the framework's built-in one, so apps can override any {@code tql.*} text. Lookup walks the
 * requested tag, then its bare language ({@code ja-JP} → {@code ja}), through every layer, and
 * optionally repeats with the app's default locale — a missing translation degrades to the
 * default language, then to the key itself at the call site, never to an error.
 *
 * <p>Message templates may contain <code>{name}</code> placeholders interpolated by
 * {@link #interpolate(String, Map)} — the same syntax the Hypermedia Components client catalog
 * uses, so one catalog serves both sides.
 */
public final class MessageCatalog {

    /** TQL-YAML-1007: a message catalog file is malformed (filename or content). */
    public static final TqlErrorCode INVALID_CATALOG = new TqlErrorCode(TqlDomain.YAML, 1007);

    private static final MessageCatalog EMPTY = new MessageCatalog(Map.of(), null);

    /** Normalized language tag → flat key → message template. */
    private final Map<String, Map<String, String>> catalogs;
    private final MessageCatalog fallback;

    private MessageCatalog(Map<String, Map<String, String>> catalogs, MessageCatalog fallback) {
        this.catalogs = catalogs;
        this.fallback = fallback;
    }

    public static MessageCatalog empty() {
        return EMPTY;
    }

    /**
     * Loads every {@code <locale>.yml} file in {@code messagesDir} (an app home's
     * {@code messages/} directory); a missing directory is an empty catalog.
     */
    public static MessageCatalog load(Path messagesDir) {
        if (!Files.isDirectory(messagesDir)) {
            return EMPTY;
        }
        Map<String, Map<String, String>> catalogs = new TreeMap<>();
        try (Stream<Path> files = Files.list(messagesDir)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        String stem = file.getFileName().toString();
                        stem = stem.substring(0, stem.length() - ".yml".length());
                        try (InputStream in = Files.newInputStream(file)) {
                            catalogs.put(normalizeTag(stem, file.toString()),
                                    parseContent(in, file.toString()));
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // Not Map.copyOf: iteration order must stay deterministic (tag-sorted) so derived
        // artifacts (coverage declarations, the client catalog module) are reproducible.
        return catalogs.isEmpty()
                ? EMPTY
                : new MessageCatalog(java.util.Collections.unmodifiableMap(catalogs), null);
    }

    /** Parses one catalog (e.g. a classpath resource) for the given language tag. */
    public static MessageCatalog parse(String tag, InputStream content, String source) {
        return new MessageCatalog(
                Map.of(normalizeTag(tag, source), parseContent(content, source)), null);
    }

    /** This catalog layered over {@code fallback}: own entries win, fallback fills the gaps. */
    public MessageCatalog withFallback(MessageCatalog fallback) {
        return new MessageCatalog(catalogs, fallback);
    }

    /** The language tags with a catalog in any layer. */
    public Set<String> tags() {
        Set<String> tags = new LinkedHashSet<>(catalogs.keySet());
        if (fallback != null) {
            tags.addAll(fallback.tags());
        }
        return tags;
    }

    /** The flat entries of one layer's exact tag (no language or fallback merging); for lint. */
    public Map<String, String> entries(String tag) {
        return catalogs.getOrDefault(normalizeTag(tag, tag), Map.of());
    }

    /**
     * Every entry visible to {@code tag}: bare-language entries under exact-tag entries, fallback
     * layers under own — what the client-side catalog for that locale should contain.
     */
    public Map<String, String> forLocale(String tag) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (fallback != null) {
            merged.putAll(fallback.forLocale(tag));
        }
        String normalized = normalizeTag(tag, tag);
        String language = new Locale.Builder().setLanguageTag(normalized).build().getLanguage();
        if (!language.equals(normalized)) {
            merged.putAll(catalogs.getOrDefault(language, Map.of()));
        }
        merged.putAll(catalogs.getOrDefault(normalized, Map.of()));
        return merged;
    }

    /** Resolves {@code key} for {@code tag} (exact, then bare language, every layer); null if absent. */
    public String resolve(String tag, String key) {
        String normalized = normalizeTag(tag, tag);
        String exact = lookup(normalized, key);
        if (exact != null) {
            return exact;
        }
        String language = new Locale.Builder().setLanguageTag(normalized).build().getLanguage();
        return language.equals(normalized) ? null : lookup(language, key);
    }

    /** {@link #resolve(String, String)}, retrying with the app's default locale; null if absent. */
    public String resolve(String tag, String defaultTag, String key) {
        String message = resolve(tag, key);
        if (message != null || defaultTag == null || defaultTag.equals(tag)) {
            return message;
        }
        return resolve(defaultTag, key);
    }

    private String lookup(String normalizedTag, String key) {
        Map<String, String> entries = catalogs.get(normalizedTag);
        if (entries != null && entries.containsKey(key)) {
            return entries.get(key);
        }
        return fallback == null ? null : fallback.lookup(normalizedTag, key);
    }

    /**
     * Interpolates <code>{name}</code> placeholders from {@code params}; unknown placeholders stay
     * as written so a missing parameter degrades to readable text.
     */
    public static String interpolate(String template, Map<String, ?> params) {
        if (template == null || params == null || params.isEmpty()
                || template.indexOf('{') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\w+)}")
                .matcher(template);
        while (matcher.find()) {
            Object value = params.get(matcher.group(1));
            matcher.appendReplacement(out, java.util.regex.Matcher
                    .quoteReplacement(value == null ? matcher.group() : String.valueOf(value)));
        }
        return matcher.appendTail(out).toString();
    }

    private static String normalizeTag(String tag, String source) {
        Locale locale = Locale.forLanguageTag(tag.trim());
        if (locale.getLanguage().isEmpty()) {
            throw new TqlException(INVALID_CATALOG, "Message catalog '" + source
                    + "': '" + tag + "' is not a BCP-47 language tag (expected e.g. en, ja-JP)");
        }
        return locale.toLanguageTag();
    }

    private static Map<String, String> parseContent(InputStream content, String source) {
        Object tree;
        try {
            tree = new ObjectMapper(new YAMLFactory()).readValue(content, Object.class);
        } catch (IOException ex) {
            throw new TqlException(INVALID_CATALOG, "Message catalog '" + source
                    + "' is not valid YAML: " + ex.getMessage());
        }
        if (tree == null) {
            return Map.of();
        }
        if (!(tree instanceof Map)) {
            throw new TqlException(INVALID_CATALOG, "Message catalog '" + source
                    + "' must be a map of message keys to texts");
        }
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", tree, flat, source);
        return java.util.Collections.unmodifiableMap(flat);
    }

    private static void flatten(String prefix, Object node, Map<String, String> into,
            String source) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty()
                        ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), into, source);
            }
            return;
        }
        if (node instanceof List) {
            throw new TqlException(INVALID_CATALOG, "Message catalog '" + source + "': key '"
                    + prefix + "' holds a list; message values must be texts");
        }
        into.put(prefix, node == null ? "" : String.valueOf(node));
    }
}
