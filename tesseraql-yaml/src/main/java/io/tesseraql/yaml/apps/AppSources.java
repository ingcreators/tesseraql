package io.tesseraql.yaml.apps;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Discovers the additional app sources to mount alongside the main app (design ch. 32):
 * {@link AppSourceProvider}s found via {@link ServiceLoader} (jar-bundled system apps), and
 * directories listed in configuration as {@code tesseraql.apps.<name>.path}. Any app can be turned
 * off with {@code tesseraql.apps.<name>.enabled: false}; duplicate app names are rejected.
 */
public final class AppSources {

    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.YAML, 1205);

    private AppSources() {
    }

    /** All enabled app sources: ServiceLoader-provided first, then config-mounted directories. */
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
        for (AppSource source : configuredDirectories(config)) {
            register(sources, source);
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

    private static List<AppSource> configuredDirectories(AppConfig config) {
        Object apps = config.navigate("tesseraql.apps");
        if (!(apps instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<AppSource> sources = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            config.getString("tesseraql.apps." + name + ".path")
                    .ifPresent(path -> sources.add(new DirectoryAppSource(name, Path.of(path))));
        }
        return sources;
    }

    private static boolean enabled(AppConfig config, String name) {
        return config.getString("tesseraql.apps." + name + ".enabled")
                .map(Boolean::parseBoolean)
                .orElse(true);
    }
}
