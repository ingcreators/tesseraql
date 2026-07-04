package io.tesseraql.yaml.account;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The application's declared preference groups ({@code config/preferences.yml}, roadmap
 * Phase 48 slice 5): fields the bundled account surface renders as an app settings section,
 * persisted under {@code app.<key>} and read back through the {@code preference.<key>}
 * request namespace with the declared default when the user never chose. Opt-in: an app
 * without the file declares nothing, and only declared keys are ever writable through the
 * account surface.
 *
 * <p>File shape:
 * <pre>
 * preferences:
 *   - { key: pageSize, label: app.pref.pageSize, type: choice, options: ["10", "25"], default: "25" }
 *   - { key: betaBanner, type: boolean, default: "false" }
 * </pre>
 * {@code type} is {@code boolean} | {@code choice} | {@code text}; {@code label} is a
 * message-catalog key (the account page falls back to the raw key when untranslated).
 */
public final class PreferencesSpec {

    /** TQL-YAML-1030: a malformed preferences.yml (parse error, bad key, duplicate key). */
    public static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.YAML, 1030);

    /** The allowed field types. */
    public static final Set<String> TYPES = Set.of("boolean", "choice", "text");

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final ConcurrentHashMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /** One declared preference field. */
    public record Field(String key, String label, String type, List<String> options,
            String defaultValue) {

        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    private record Cached(FileTime modified, long size, PreferencesSpec spec) {
    }

    private final List<Field> fields;

    private PreferencesSpec(List<Field> fields) {
        this.fields = List.copyOf(fields);
    }

    public List<Field> fields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** The declared field for a key, or {@code null} — undeclared keys are never writable. */
    public Field field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst()
                .orElse(null);
    }

    /** Loads {@code config/preferences.yml}; an absent file declares nothing. */
    public static PreferencesSpec load(Path appHome) {
        Path file = appHome.resolve("config/preferences.yml").normalize();
        if (!Files.isRegularFile(file)) {
            return new PreferencesSpec(List.of());
        }
        Object root;
        try {
            root = new SimpleYamlParser().parseTree(file);
        } catch (RuntimeException ex) {
            throw new TqlException(INVALID,
                    "config/preferences.yml does not parse: " + ex.getMessage());
        }
        Object declared = root instanceof Map<?, ?> map ? map.get("preferences") : null;
        if (declared == null) {
            return new PreferencesSpec(List.of());
        }
        if (!(declared instanceof List<?> entries)) {
            throw new TqlException(INVALID, "preferences: must be a list of fields");
        }
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new TqlException(INVALID, "Each preference must be a map of settings");
            }
            Field field = parseField(raw);
            if (!seen.add(field.key())) {
                throw new TqlException(INVALID, "Duplicate preference key '" + field.key() + "'");
            }
            fields.add(field);
        }
        return new PreferencesSpec(fields);
    }

    /**
     * The current declarations for {@code appHome}, re-read only when the file changed — the
     * {@link io.tesseraql.yaml.menu.MenuSpec#live} contract, so an edit takes effect on the
     * next render and an unchanged file costs a single stat.
     */
    public static PreferencesSpec live(Path appHome) {
        Path file = appHome.resolve("config/preferences.yml").normalize();
        FileTime modified;
        long size;
        try {
            if (Files.isRegularFile(file)) {
                modified = Files.getLastModifiedTime(file);
                size = Files.size(file);
            } else {
                modified = null;
                size = -1L;
            }
        } catch (IOException ex) {
            return load(appHome);
        }
        Cached cached = CACHE.get(appHome);
        if (cached != null && Objects.equals(cached.modified(), modified)
                && cached.size() == size) {
            return cached.spec();
        }
        PreferencesSpec spec = load(appHome);
        CACHE.put(appHome, new Cached(modified, size, spec));
        return spec;
    }

    /** Whether a value is acceptable for the declared field (roadmap Phase 48). */
    public static boolean accepts(Field field, String value) {
        if (value == null) {
            return false;
        }
        return switch (field.type()) {
            case "boolean" -> "true".equals(value) || "false".equals(value);
            case "choice" -> field.options().contains(value);
            default -> value.length() <= 2000;
        };
    }

    private static Field parseField(Map<?, ?> raw) {
        String key = string(raw.get("key"));
        if (key == null || !KEY.matcher(key).matches()) {
            throw new TqlException(INVALID, "Preference key '" + key
                    + "' must match [A-Za-z][A-Za-z0-9_.-]{0,63}");
        }
        String type = string(raw.get("type"));
        if (type == null || !TYPES.contains(type)) {
            throw new TqlException(new TqlErrorCode(TqlDomain.YAML, 1031),
                    "Preference '" + key + "' has unknown type '" + type
                            + "' (boolean | choice | text)");
        }
        List<String> options = new ArrayList<>();
        if (raw.get("options") instanceof List<?> declared) {
            declared.forEach(option -> options.add(String.valueOf(option)));
        }
        if ("choice".equals(type) && options.isEmpty()) {
            throw new TqlException(new TqlErrorCode(TqlDomain.YAML, 1032),
                    "Preference '" + key + "' is a choice but declares no options");
        }
        String label = string(raw.get("label"));
        String defaultValue = string(raw.get("default"));
        Field field = new Field(key, label == null ? key : label, type, options, defaultValue);
        if (defaultValue != null && !accepts(field, defaultValue)) {
            throw new TqlException(new TqlErrorCode(TqlDomain.YAML, 1033),
                    "Preference '" + key + "' default '" + defaultValue
                            + "' is not an acceptable value");
        }
        return field;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
