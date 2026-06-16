package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code tesseraql modules}: adding a pinned coordinate edits the config, resolves it offline from
 * the local repository (no BOM needed for a pinned version), writes {@code modules.lock}, and caches
 * the jar under {@code work/modules}. Pinned to {@code info.picocli:picocli}, a leaf already present
 * in the local repository as a CLI dependency, so the resolution is hermetic.
 */
class ModulesCommandTest {

    private static final String PICOCLI = "info.picocli:picocli:4.7.7";

    @Test
    void addResolvesAPinnedModuleOfflineWritesTheLockAndCachesTheJar(@TempDir Path dir)
            throws Exception {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        Path app = dir.resolve("demo");

        assertThat(execute("modules", "add", PICOCLI, "--app", app.toString(), "--offline"))
                .isZero();

        assertThat(app.resolve("config/tesseraql.yml")).content().contains(PICOCLI);
        assertThat(app.resolve("modules.lock")).content().contains(PICOCLI);
        assertThat(app.resolve("work/modules/picocli-4.7.7.jar")).exists();

        assertThat(execute("modules", "list", "--app", app.toString())).isZero();
    }

    private static int execute(String... args) {
        return new CommandLine(new TesseraqlCli()).execute(args);
    }
}
