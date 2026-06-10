package io.tesseraql.yaml.apps;

import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/** Registered via META-INF/services in test resources to exercise ServiceLoader discovery. */
public final class TestAppSourceProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        return List.of(new ClasspathAppSource(
                "test-app", "apps/test-app", getClass().getClassLoader()));
    }
}
