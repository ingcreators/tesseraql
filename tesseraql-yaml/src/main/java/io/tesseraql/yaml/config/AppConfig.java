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

    private static final io.tesseraql.yaml.secret.SecretResolvers DEFAULT_SECRETS =
            io.tesseraql.yaml.secret.SecretResolvers.discover();

    private final Map<String, Object> root;
    private final EnvironmentSource environment;
    private final io.tesseraql.yaml.secret.SecretResolvers secrets;

    /** Source of environment values; abstracted so tests can supply deterministic values. */
    @FunctionalInterface
    public interface EnvironmentSource {
        String get(String name);
    }

    public AppConfig(Map<String, Object> root) {
        this(root, System::getenv);
    }

    public AppConfig(Map<String, Object> root, EnvironmentSource environment) {
        this(root, environment, DEFAULT_SECRETS);
    }

    public AppConfig(Map<String, Object> root, EnvironmentSource environment,
            io.tesseraql.yaml.secret.SecretResolvers secrets) {
        this.root = Map.copyOf(root);
        this.environment = environment;
        this.secrets = secrets;
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

    /** Returns the numeric value at a dotted path, or empty if the path is absent. */
    public java.util.OptionalDouble getDouble(String dottedPath) {
        Object raw = navigate(dottedPath);
        if (raw == null) {
            return java.util.OptionalDouble.empty();
        }
        if (raw instanceof Number number) {
            return java.util.OptionalDouble.of(number.doubleValue());
        }
        try {
            return java.util.OptionalDouble.of(Double.parseDouble(resolve(String.valueOf(raw), 0).trim()));
        } catch (NumberFormatException ex) {
            throw new TqlException(UNRESOLVED,
                    "Configuration key '" + dottedPath + "' is not a number: " + raw);
        }
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
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int start = value.indexOf("${", from);
            if (start < 0) {
                out.append(value, from, value.length());
                return out.toString();
            }
            int end = matchingBrace(value, start + 2);
            if (end < 0) {
                out.append(value, from, value.length());
                return out.toString();
            }
            out.append(value, from, start);
            // Substituted values are not re-scanned: config values and fallbacks expand inside
            // resolvePlaceholder, while environment and secret values stay literal so a value
            // containing ${...} can never inject further resolution.
            out.append(resolvePlaceholder(value.substring(start + 2, end), depth));
            from = end + 1;
        }
    }

    private String resolvePlaceholder(String body, int depth) {
        int colon = topLevelColon(body);
        String key = (colon < 0 ? body : body.substring(0, colon)).trim();
        String fallback = colon < 0 ? null : body.substring(colon + 1);

        // ${secret.<provider>.<name>} resolves through the secret providers (design ch. 41).
        // Secret values are literal: they never go through another round of expansion.
        if (key.startsWith("secret.")) {
            String secret = secrets.resolve(key.substring("secret.".length()));
            if (secret != null) {
                return secret;
            }
            if (fallback != null) {
                return resolve(fallback, depth + 1);
            }
            throw new TqlException(UNRESOLVED, "Cannot resolve secret '${" + body + "}'");
        }

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
