package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPackagerTest {

    @Test
    void packsDeterministicArchiveExcludingWork(@TempDir Path dir) throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome.resolve("config"));
        Files.createDirectories(appHome.resolve("work/tmp"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.writeString(appHome.resolve("work/tmp/scratch.txt"), "ignore me");

        Path out = dir.resolve("app.tqlapp");
        new AppPackager().pack(appHome, out);

        assertThat(Files.size(out)).isPositive();
        List<String> entries = entries(out);
        assertThat(entries).contains("config/tesseraql.yml");
        assertThat(entries).noneMatch(name -> name.startsWith("work/"));

        // Deterministic: re-packing yields byte-identical output.
        Path out2 = dir.resolve("app2.tqlapp");
        new AppPackager().pack(appHome, out2);
        assertThat(Files.readAllBytes(out)).isEqualTo(Files.readAllBytes(out2));
    }

    private static List<String> entries(Path zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
