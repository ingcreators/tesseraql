package io.tesseraql.apptasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
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

    @Test
    void mergesGeneratedDocsUnderTheReservedPrefix(@TempDir Path dir) throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Path generatedDocs = dir.resolve("target/tesseraql-generated/docs");
        Files.createDirectories(generatedDocs);
        Files.writeString(generatedDocs.resolve("spec.json"), "{\"routes\":[]}\n");

        Path out = dir.resolve("app.tqlapp");
        new AppPackager().pack(appHome, generatedDocs, out);

        assertThat(entries(out)).contains("config/tesseraql.yml", ".tesseraql/docs/spec.json");

        // Deterministic with the merged docs too.
        Path out2 = dir.resolve("app2.tqlapp");
        new AppPackager().pack(appHome, generatedDocs, out2);
        assertThat(Files.readAllBytes(out)).isEqualTo(Files.readAllBytes(out2));
    }

    @Test
    void excludesSourceTreeReservedNamespaceSoOverlaysNeverLeakIntoTheArchive(@TempDir Path dir)
            throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome.resolve("config"));
        Files.createDirectories(appHome.resolve(".tesseraql/docs"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        // Run-dependent overlays left in the source-tree reserved namespace (v2 report, v3 schema).
        Files.writeString(appHome.resolve(".tesseraql/docs/report.json"), "{\"runId\":\"x\"}\n");
        Files.writeString(appHome.resolve(".tesseraql/docs/schema.json"),
                "{\"schemaVersion\":1}\n");

        Path generatedDocs = dir.resolve("target/tesseraql-generated/docs");
        Files.createDirectories(generatedDocs);
        Files.writeString(generatedDocs.resolve("spec.json"), "{\"routes\":[]}\n");

        Path out = dir.resolve("app.tqlapp");
        new AppPackager().pack(appHome, generatedDocs, out);

        // The generated spec is merged in; the source-tree overlays are not packed.
        assertThat(entries(out)).contains("config/tesseraql.yml", ".tesseraql/docs/spec.json")
                .doesNotContain(".tesseraql/docs/report.json", ".tesseraql/docs/schema.json");
    }

    @Test
    void packsWithoutGeneratedDocsWhenTheDirectoryIsAbsent(@TempDir Path dir) throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");

        Path out = dir.resolve("app.tqlapp");
        new AppPackager().pack(appHome, dir.resolve("does-not-exist"), out);

        assertThat(entries(out)).contains("config/tesseraql.yml")
                .noneMatch(name -> name.startsWith(".tesseraql/"));
    }

    @Test
    void carriesTheResolvedModuleClosureUnderTheReservedPrefix(@TempDir Path dir)
            throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Path modules = dir.resolve("work/modules");
        Files.createDirectories(modules);
        Files.writeString(modules.resolve("tesseraql-pdf-0.1.0.jar"), "jar bytes");
        Files.writeString(modules.resolve("notes.txt"), "not a jar");

        Path out = dir.resolve("app.tqlapp");
        new AppPackager().pack(appHome, null, modules, out);

        assertThat(entries(out)).contains(".tesseraql/modules/tesseraql-pdf-0.1.0.jar")
                .doesNotContain(".tesseraql/modules/notes.txt");

        // Deterministic with the modules too: same lock, same closure, same bytes.
        Path out2 = dir.resolve("app2.tqlapp");
        new AppPackager().pack(appHome, null, modules, out2);
        assertThat(Files.readAllBytes(out)).isEqualTo(Files.readAllBytes(out2));
    }

    @Test
    void declaredModulesWithoutALockAreRefused(@TempDir Path dir) throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome);
        io.tesseraql.yaml.config.AppConfig config = new io.tesseraql.yaml.config.AppConfig(
                java.util.Map.of("tesseraql",
                        java.util.Map.of("modules", List.of("io.tesseraql:tesseraql-pdf"))));

        assertThatThrownBy(() -> PackagedModules.requireLock(appHome, config))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-APP-4218")
                .hasMessageContaining("modules resolve");
    }

    @Test
    void aClosureThatDisagreesWithTheLockIsRefused(@TempDir Path dir) throws Exception {
        Path appHome = dir.resolve("app");
        Path modules = appHome.resolve("work/modules");
        Files.createDirectories(modules);
        Files.writeString(modules.resolve("codec-1.0.jar"), "resolved bytes");
        Path lock = appHome.resolve("modules.lock");
        Files.writeString(lock, "{\"artifacts\":[{\"coordinate\":\"g:codec:1.0\","
                + "\"sha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"}]}");

        assertThatThrownBy(() -> PackagedModules.verifyAgainstLock(appHome, modules, lock))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-APP-4219");

        // The same closure, locked by its real checksum, passes.
        Files.writeString(lock, "{\"artifacts\":[{\"coordinate\":\"g:codec:1.0\",\"sha256\":\""
                + io.tesseraql.core.util.Hashing.sha256(modules.resolve("codec-1.0.jar"))
                + "\"}]}");
        PackagedModules.verifyAgainstLock(appHome, modules, lock);
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
