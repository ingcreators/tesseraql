package io.tesseraql.runtime;

import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.yaml.config.AppConfig;

/**
 * A runtime extension packaged into a plugin jar by {@link RuntimeExtensionsTest}. It is not
 * registered in the test classpath's {@code META-INF/services}, so it is only discoverable through
 * a plugin jar's service entry.
 */
public class TestPluginExtension implements RuntimeExtension {

    @Override
    public String name() {
        return "test-plugin";
    }

    @Override
    public boolean enabled(AppConfig config) {
        return true;
    }

    @Override
    public void install(ExtensionContext context) {
    }
}
