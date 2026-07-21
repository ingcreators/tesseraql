package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The offline halves of {@code tesseraql duckdb} (docs/duckdb.md): cache layout and bundles. */
class DuckDbCommandTest {

    private static Path stageApp(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/main
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [postgres]
                """);
        return dir;
    }

    @Test
    void collectsDeclaredExtensionsAndDefaultCacheDirectory(@TempDir Path dir) throws Exception {
        stageApp(dir);
        AppConfig config = new ManifestLoader().load(dir).config();

        assertThat(DuckDbCommand.declaredExtensions(config)).containsExactly("postgres");
        assertThat(DuckDbCommand.extensionDirectory(config, dir))
                .isEqualTo(dir.resolve("work/duckdb-extensions").toAbsolutePath().normalize());
    }

    @Test
    void bundlesRoundTripTheCacheLayout(@TempDir Path dir) throws Exception {
        Path cache = Files.createDirectories(dir.resolve("cache/v1.3.1/linux_amd64"));
        Files.writeString(cache.resolve("postgres_scanner.duckdb_extension"), "fake-binary");
        Path bundle = dir.resolve("duckdb-ext.zip");

        DuckDbCommand.zip(dir.resolve("cache"), bundle);
        Path restored = Files.createDirectories(dir.resolve("restored"));
        DuckDbCommand.unzip(bundle, restored);

        assertThat(restored.resolve("v1.3.1/linux_amd64/postgres_scanner.duckdb_extension"))
                .exists()
                .hasContent("fake-binary");
    }

    @Test
    void refusesABundleEntryEscapingTheCache(@TempDir Path dir) throws Exception {
        Path bundle = dir.resolve("evil.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("../escape.txt"));
            zip.write("x".getBytes());
            zip.closeEntry();
        }

        assertThatThrownBy(() -> DuckDbCommand.unzip(bundle, dir.resolve("cache")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the cache");
    }
}
