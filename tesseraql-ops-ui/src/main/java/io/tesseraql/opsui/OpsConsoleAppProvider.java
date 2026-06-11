package io.tesseraql.opsui;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled operations console app (design ch. 26.11, 32): a yaml/template tree
 * served under {@code /_tesseraql/ops/console}, rendering the {@code ops.*} service providers.
 * Mounted automatically when this jar is on the classpath; disable with
 * {@code tesseraql.apps.ops-console.enabled: false}.
 */
public final class OpsConsoleAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        return List.of(new ClasspathAppSource(
                "ops-console", "tesseraql/apps/ops-console", getClass().getClassLoader()));
    }
}
