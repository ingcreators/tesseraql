package io.tesseraql.yaml.apps;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Discovers the framework's own apps to mount alongside the application (design ch. 32): the
 * {@link AppSourceProvider}s found via {@link ServiceLoader} — Studio, the operations console,
 * IAM Admin, the account surface and the sign-in pages. Any of them can be turned off with
 * {@code tesseraql.apps.<name>.enabled: false}; duplicate names are rejected.
 *
 * <p>One runtime serves <strong>one</strong> user application plus these
 * (docs/app-isolation-model.md decision 1). Mounting further user applications here —
 * {@code tesseraql.apps.<name>.path}/{@code .package}/{@code .url} — was the framework's other
 * multi-app mechanism, and it shared everything: one URL space with no per-app prefix, a Studio
 * that could not see the mounted apps, and one trace buffer for all of them. Several
 * applications are hosted by {@code tesseraql host}, which gives each its own runtime.
 */
public final class AppSources {

    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.YAML, 1205);

    private AppSources() {
    }

    /** Every enabled system app source. */
    public static List<AppSource> discover(AppConfig config) {
        return discover(config, ServiceLoader.load(AppSourceProvider.class));
    }

    static List<AppSource> discover(AppConfig config, Iterable<AppSourceProvider> providers) {
        Map<String, AppSource> sources = new LinkedHashMap<>();
        for (AppSourceProvider provider : providers) {
            for (AppSource source : provider.appSources(config)) {
                register(sources, source);
            }
        }
        return sources.values().stream()
                .filter(source -> enabled(config, source.name()))
                .toList();
    }

    private static void register(Map<String, AppSource> sources, AppSource source) {
        if (sources.putIfAbsent(source.name(), source) != null) {
            throw new TqlException(DUPLICATE, "Duplicate app source name: " + source.name());
        }
    }

    private static boolean enabled(AppConfig config, String name) {
        return config.getString("tesseraql.apps." + name + ".enabled")
                .map(Boolean::parseBoolean)
                .orElse(true);
    }
}
