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

    @Test
    void mountsConfiguredDirectories() throws Exception {
        Files.createDirectories(dir.resolve("extra"));
        AppConfig config = config(Map.of("tesseraql", Map.of("apps",
                Map.of("extra", Map.of("path", dir.resolve("extra").toString())))));

        List<AppSource> sources = AppSources.discover(config);

        assertThat(sources).extracting(AppSource::name).contains("extra", "test-app");
        AppSource extra = sources.stream().filter(s -> s.name().equals("extra")).findFirst()
                .orElseThrow();
        assertThat(extra.materialize(dir)).isEqualTo(dir.resolve("extra"));
    }

    @Test
    void mountsConfiguredPackages() throws Exception {
        Path tqlapp = dir.resolve("demo.tqlapp");
        try (var out = Files.newOutputStream(tqlapp);
                var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("web/ping/get.yml"));
            zip.write("version: tesseraql/v1\nid: ping\n".getBytes());
            zip.closeEntry();
        }
        AppConfig config = config(Map.of("tesseraql", Map.of("apps",
                Map.of("demo", Map.of("package", tqlapp.toString())))));

        List<AppSource> sources = AppSources.discover(config);

        AppSource demo = sources.stream().filter(s -> s.name().equals("demo")).findFirst()
                .orElseThrow();
        assertThat(demo).isInstanceOf(ZipAppSource.class);
        assertThat(Files.readString(demo.materialize(dir).resolve("web/ping/get.yml")))
                .contains("id: ping");
    }

    @Test
    void duplicateNamesAreRejected() {
        AppSourceProvider duplicate = config -> List.of(
                new DirectoryAppSource("dup", dir), new DirectoryAppSource("dup", dir));

        assertThatThrownBy(() -> AppSources.discover(config(Map.of()), List.of(duplicate)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Duplicate app source name");
    }
}
