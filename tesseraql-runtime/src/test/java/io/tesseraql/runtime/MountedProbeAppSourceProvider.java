package io.tesseraql.runtime;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * A test-scoped app source (META-INF/services in the test resources): mounts the directory the
 * {@code tesseraql.test.mount.dir} system property names as the app {@code mounted-probe}, and nothing
 * when the property is unset — so every other runtime test sees no extra app.
 */
public final class MountedProbeAppSourceProvider implements AppSourceProvider {

    static final String PROPERTY = "tesseraql.test.mount.dir";

    @Override
    public List<AppSource> appSources(AppConfig config) {
        String dir = System.getProperty(PROPERTY);
        if (dir == null || dir.isBlank()) {
            return List.of();
        }
        return List.of(new AppSource() {
            @Override
            public String name() {
                return "mounted-probe";
            }

            @Override
            public Path materialize(Path workRoot) {
                return Path.of(dir);
            }
        });
    }
}
