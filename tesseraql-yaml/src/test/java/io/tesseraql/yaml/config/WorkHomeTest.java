package io.tesseraql.yaml.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkHomeTest {

    private static final Path HOME = Path.of("/apps/demo");

    /**
     * The module set is the bundled directory when it holds a jar, else {@code work/modules}
     * (docs/codec-discovery.md decision 4): one answer for the runtime and every CLI verb.
     */
    @Test
    void theModuleSetIsTheBundledDirectoryWhenItHoldsAJarElseWorkModules(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        AppConfig config = new AppConfig(Map.of(), name -> null);
        assertThat(WorkHome.moduleSet(dir, config)).isEqualTo(dir.resolve("work/modules"));

        Path bundled = java.nio.file.Files.createDirectories(dir.resolve(WorkHome.BUNDLED_MODULES));
        java.nio.file.Files.writeString(bundled.resolve("notes.txt"), "");
        assertThat(WorkHome.moduleSet(dir, config)).as("a jar-less bundled directory is not a set")
                .isEqualTo(dir.resolve("work/modules"));

        java.nio.file.Files.writeString(bundled.resolve("codec.jar"), "");
        assertThat(WorkHome.moduleSet(dir, config)).isEqualTo(bundled);
    }

    @Test
    void defaultsToTheConventionalWorkDirectory() {
        assertThat(WorkHome.resolve(HOME, new AppConfig(Map.of(), name -> null)))
                .isEqualTo(HOME.resolve("work"));
    }

    @Test
    void theScaffoldedPlaceholderChainFallsBackOutsideTheLauncher() {
        // ${TESSERAQL_WORK_HOME:${TESSERAQL_APP_HOME}/work} with neither variable set cannot
        // resolve; that is the conventional layout, not an error.
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of("app",
                Map.of("work", "${TESSERAQL_WORK_HOME:${TESSERAQL_APP_HOME}/work}"))),
                name -> null);

        assertThat(WorkHome.resolve(HOME, config)).isEqualTo(HOME.resolve("work"));
    }

    @Test
    void aDeclaredLocationRelocatesEverything() {
        AppConfig absolute = new AppConfig(Map.of("tesseraql", Map.of("app",
                Map.of("work", "/var/tesseraql/demo-work"))), name -> null);
        assertThat(WorkHome.resolve(HOME, absolute))
                .isEqualTo(Path.of("/var/tesseraql/demo-work"));

        AppConfig relative = new AppConfig(Map.of("tesseraql", Map.of("app",
                Map.of("work", "../shared-work"))), name -> null);
        assertThat(WorkHome.resolve(HOME, relative))
                .isEqualTo(Path.of("/apps/shared-work"));

        AppConfig env = new AppConfig(Map.of("tesseraql", Map.of("app",
                Map.of("work", "${TESSERAQL_WORK_HOME:${TESSERAQL_APP_HOME}/work}"))),
                name -> "TESSERAQL_WORK_HOME".equals(name) ? "/mnt/work" : null);
        assertThat(WorkHome.resolve(HOME, env)).isEqualTo(Path.of("/mnt/work"));
    }
}
