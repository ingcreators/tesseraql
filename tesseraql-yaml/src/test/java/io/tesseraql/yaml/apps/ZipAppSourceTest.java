package io.tesseraql.yaml.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipAppSourceTest {

    @TempDir
    Path dir;

    private Path zip(String fileName, Map<String, String> entries) throws Exception {
        Path file = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void extractsPackageIntoWorkDirectory() throws Exception {
        Path tqlapp = zip("demo.tqlapp", Map.of(
                "web/ping/get.yml", "version: tesseraql/v1\nid: ping\n",
                "web/ping/ping.sql", "select 1\n;\n"));

        Path root = new ZipAppSource("demo", tqlapp).materialize(dir.resolve("work"));

        assertThat(root).isEqualTo(dir.resolve("work/demo"));
        assertThat(Files.readString(root.resolve("web/ping/get.yml"))).contains("id: ping");
        assertThat(Files.readString(root.resolve("web/ping/ping.sql"))).contains("select");
    }

    @Test
    void reextractionRemovesStaleFiles() throws Exception {
        Path workRoot = dir.resolve("work");
        Path v1 = zip("v1.tqlapp", Map.of("web/a/get.yml", "a", "web/old/get.yml", "old"));
        new ZipAppSource("demo", v1).materialize(workRoot);

        // The next package no longer contains web/old; the extraction must not leave it behind.
        Path v2 = zip("v2.tqlapp", Map.of("web/a/get.yml", "a2"));
        Path root = new ZipAppSource("demo", v2).materialize(workRoot);

        assertThat(Files.readString(root.resolve("web/a/get.yml"))).isEqualTo("a2");
        assertThat(root.resolve("web/old/get.yml")).doesNotExist();
    }

    @Test
    void zipSlipEntryIsRejected() throws Exception {
        Path evil = zip("evil.tqlapp", Map.of("../outside.txt", "boom"));

        assertThatThrownBy(() -> new ZipAppSource("evil", evil).materialize(dir.resolve("work")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes the app root");
    }

    @Test
    void verifiesPackageIntegrityWhenHashGiven() throws Exception {
        Path tqlapp = zip("demo.tqlapp", Map.of("web/ping/get.yml", "id: ping\n"));
        String good = io.tesseraql.core.util.Hashing.sha256(tqlapp);

        // The matching hash extracts normally.
        assertThat(new ZipAppSource("demo", tqlapp, good).materialize(dir.resolve("work")))
                .exists();

        // A tampered or wrong package is rejected before extraction.
        assertThatThrownBy(() -> new ZipAppSource("demo", tqlapp, "deadbeef")
                .materialize(dir.resolve("work2")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("integrity check failed");
    }

    @Test
    void missingPackageFails() {
        assertThatThrownBy(() -> new ZipAppSource("ghost", dir.resolve("nope.tqlapp"))
                .materialize(dir.resolve("work")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("package does not exist");
    }
}
