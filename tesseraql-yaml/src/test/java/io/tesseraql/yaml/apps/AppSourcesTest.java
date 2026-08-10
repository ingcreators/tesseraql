package io.tesseraql.yaml.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSourcesTest {

    @TempDir
    Path dir;

    private static AppConfig config(Map<String, Object> root) {
        return new AppConfig(root, name -> null);
    }

    @Test
    void discoversServiceLoaderProviders() {
        List<AppSource> sources = AppSources.discover(config(Map.of()));

        assertThat(sources).extracting(AppSource::name).contains("test-app");
    }

    @Test
    void configCanDisableAProvidedApp() {
        AppConfig config = config(Map.of("tesseraql", Map.of("apps",
                Map.of("test-app", Map.of("enabled", "false")))));

        assertThat(AppSources.discover(config)).extracting(AppSource::name)
                .doesNotContain("test-app");
    }

    /**
     * A user application is no longer mountable here (docs/app-isolation-model.md decision 1).
     * The keys are simply not read any more: one runtime serves one application plus the
     * framework's own surfaces, and several applications are hosted by {@code tesseraql host},
     * which gives each its own runtime, URL space, Studio and traces.
     */
    @Test
    void aConfiguredUserApplicationIsNotMounted() throws Exception {
        Files.createDirectories(dir.resolve("extra"));
        AppConfig config = config(Map.of("tesseraql", Map.of("apps",
                Map.of("extra", Map.of("path", dir.resolve("extra").toString())))));

        assertThat(AppSources.discover(config)).extracting(AppSource::name)
                .containsExactly("test-app");
    }

    @Test
    void duplicateNamesAreRejected() {
        AppSource same = new ClasspathAppSource("dup", "tesseraql/apps/test-app",
                AppSourcesTest.class.getClassLoader());
        AppSourceProvider duplicate = config -> List.of(same, same);

        assertThatThrownBy(() -> AppSources.discover(config(Map.of()), List.of(duplicate)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Duplicate app source name");
    }
}
