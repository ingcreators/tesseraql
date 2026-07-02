package io.tesseraql.yaml.flags;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The application's live feature flags ({@code config/flags.yml}, a {@code flags:} map of name to a
 * scalar value). The flags are injected into the request evaluation context as {@code flags.<name>},
 * so routes, templates, and validation rules can gate on them, and they are read {@linkplain
 * #live(Path) live} — a Studio flag edit takes effect on the next request without a restart.
 *
 * <p>File shape:
 * <pre>
 * flags:
 *   betaCheckout: true
 *   maxItems: 10
 *   bannerText: "Maintenance at 2am"
 * </pre>
 */
public final class FlagsSpec {

    private static final TqlErrorCode WRITE_ERROR = new TqlErrorCode(TqlDomain.YAML, 1011);

    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    private static final FlagsSpec EMPTY = new FlagsSpec(Map.of());

    private record Cached(FileTime modified, long size, FlagsSpec spec) {
    }

    private static final ConcurrentHashMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private final Map<String, Object> values;

    private FlagsSpec(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    /** The empty flag set (an app with no {@code config/flags.yml}). */
    public static FlagsSpec empty() {
        return EMPTY;
    }

    /** Loads {@code config/flags.yml} under {@code appHome}; an empty set when the file is absent. */
    public static FlagsSpec load(Path appHome) {
        Path file = appHome.resolve("config/flags.yml").normalize();
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        Object raw = new SimpleYamlParser().parseTree(file).get("flags");
        if (!(raw instanceof Map<?, ?> map)) {
            return EMPTY;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key), value));
        return new FlagsSpec(values);
    }

    /**
     * The current flags for {@code appHome}, re-reading {@code config/flags.yml} only when its
     * last-modified time or size has changed — so a flag edit is served on the next request without a
     * restart, while an unchanged file costs a single {@code stat}.
     */
    public static FlagsSpec live(Path appHome) {
        Path file = appHome.resolve("config/flags.yml").normalize();
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
        FlagsSpec spec = load(appHome);
        CACHE.put(appHome, new Cached(modified, size, spec));
        return spec;
    }

    /** The flag name → value map (values are booleans/numbers/strings as written). */
    public Map<String, Object> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Serializes flags to the {@code config/flags.yml} document text — the inverse of {@link #load}. */
    public static String toYaml(Map<String, Object> values) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("flags", values);
        try {
            return YAML.writeValueAsString(doc);
        } catch (JsonProcessingException ex) {
            throw new TqlException(WRITE_ERROR, "Failed to serialize flags.yml");
        }
    }
}
