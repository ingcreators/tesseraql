package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Map;
import java.util.Optional;

/**
 * Merged, placeholder-resolving view over {@code application.yml} and {@code tesseraql.yml}
 * (design ch. 5, 32.10).
 *
 * <p>Both files are merged into a single tree so that {@code tesseraql.yml} can reference keys
 * declared in {@code application.yml} (for example {@code ${db.main.url}}). Placeholders of the
 * form {@code ${key}} or {@code ${key:default}} are resolved against, in order: an environment
 * variable named {@code key}, the merged config at the dotted path {@code key}, then the default.
 * Defaults may themselves contain placeholders (for example
 * {@code ${TESSERAQL_WORK_HOME:${TESSERAQL_APP_HOME}/work}}).
 */
public final class AppConfig {

    private static final TqlErrorCode UNRESOLVED = new TqlErrorCode(TqlDomain.YAML, 1101);
    private static final int MAX_DEPTH = 32;

    private final Map<String, Object> root;
    private final EnvironmentSource environment;

    /** Source of environment values; abstracted so tests can supply deterministic values. */
    @FunctionalInterface
    public interface EnvironmentSource {
        String get(String name);
    }

    public AppConfig(Map<String, Object> root) {
        this(root, System::getenv);
    }

    public AppConfig(Map<String, Object> root, EnvironmentSource environment) {
        this.root = Map.copyOf(root);
        this.environment = environment;
    }

    /** Returns the resolved string value at a dotted path, or empty if the path is absent. */
    public Optional<String> getString(String dottedPath) {
        Object raw = navigate(dottedPath);
        return raw == null ? Optional.empty() : Optional.of(resolve(String.valueOf(raw), 0));
    }

    /** Returns the resolved string value at a dotted path or throws if absent. */
    public String requireString(String dottedPath) {
        return getString(dottedPath).orElseThrow(() -> new TqlException(
                UNRESOLVED, "Missing required configuration key: " + dottedPath));
    }

    /** Returns the raw (unresolved) node at a dotted path, or {@code null}. */
    public Object navigate(String dottedPath) {
        Object current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Resolves placeholders in an arbitrary string value. */
    public String resolve(String value) {
        return resolve(value, 0);
    }

    private String resolve(String value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new TqlException(UNRESOLVED, "Placeholder resolution too deep (cycle?): " + value);
        }
        int start = value.indexOf("${");
        if (start < 0) {
            return value;
        }
        int end = matchingBrace(value, start + 2);
        if (end < 0) {
            return value;
        }
        String body = value.substring(start + 2, end);
        String resolved = resolvePlaceholder(body, depth);
        String replaced = value.substring(0, start) + resolved + value.substring(end + 1);
        return resolve(replaced, depth + 1);
    }

    private String resolvePlaceholder(String body, int depth) {
        int colon = topLevelColon(body);
        String key = (colon < 0 ? body : body.substring(0, colon)).trim();
        String fallback = colon < 0 ? null : body.substring(colon + 1);

        String env = environment.get(key);
        if (env != null) {
            return env;
        }
        Object configValue = navigate(key);
        if (configValue != null) {
            return resolve(String.valueOf(configValue), depth + 1);
        }
        if (fallback != null) {
            return resolve(fallback, depth + 1);
        }
        throw new TqlException(UNRESOLVED, "Cannot resolve placeholder '${" + body + "}'");
    }

    private static int matchingBrace(String value, int from) {
        int depth = 1;
        for (int i = from; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int topLevelColon(String body) {
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
