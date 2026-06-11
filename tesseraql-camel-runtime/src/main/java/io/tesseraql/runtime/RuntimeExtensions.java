package io.tesseraql.runtime;

import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.plugins.Plugins;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the runtime extensions to install (design ch. 47): ServiceLoader providers from the
 * runtime classpath plus providers inside verified plugin jars ({@code tesseraql.plugins.dir}),
 * each plugin in its own isolated class loader. When {@code tesseraql.plugins.allowlist} is
 * configured, only listed extension names pass - whether they arrived as a plugin or on the
 * classpath.
 */
final class RuntimeExtensions {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeExtensions.class);

    private RuntimeExtensions() {
    }

    static List<RuntimeExtension> discover(AppConfig config, Path appHome) {
        // Keyed by implementation class so a provider visible through both the classpath and a
        // plugin loader's parent delegation is only installed once.
        Map<String, RuntimeExtension> byClass = new LinkedHashMap<>();
        ServiceLoader.load(RuntimeExtension.class).forEach(
                extension -> byClass.putIfAbsent(extension.getClass().getName(), extension));
        for (Plugins.PluginJar plugin : Plugins.load(config, appHome)) {
            LOG.info("Loaded plugin jar '{}'", plugin.name());
            ServiceLoader.load(RuntimeExtension.class, plugin.classLoader())
                    .forEach(extension -> byClass.putIfAbsent(extension.getClass().getName(),
                            extension));
        }
        List<RuntimeExtension> extensions = new ArrayList<>();
        for (RuntimeExtension extension : byClass.values()) {
            if (!Plugins.allowed(config, extension.name())) {
                LOG.warn("Runtime extension '{}' is not on tesseraql.plugins.allowlist - skipped",
                        extension.name());
                continue;
            }
            extensions.add(extension);
        }
        return extensions;
    }
}
