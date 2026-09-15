package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.cli.modules.ModulesYaml;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dev --app-name} runs one member and touches one member (docs/codec-discovery.md S5). The
 * per-member steps around the gateway — resolving declared modules, writing the embedded-database
 * marker, printing the first-administrator hint — walk the members the run starts, not the stack.
 * Measured 2026-09-15 at {@code e7107ca09} on the examples: {@code --app-name user-admin} resolved
 * inventory-app's 73 MB DuckDB driver, left a {@code work/} tree under all seven members and
 * printed seven hints for one running application.
 *
 * <p>Both cases fork the command, because {@code dev} parks until interrupted. The module the idle
 * member declares is pinned to a leaf the local repository already holds as a CLI dependency, and
 * the run is {@code --offline}: on the defect it resolves hermetically, and after the fix it is
 * never resolved at all.
 */
class DevNarrowingIntegrationTest {

    private static final String PICOCLI = "info.picocli:picocli:4.7.7";

    /**
     * Linux and macOS only, like every case that stops a running {@code dev}:
     * {@link Process#destroy()} is {@code TerminateProcess} on Windows, runs no shutdown hook, and
     * would orphan the embedded PostgreSQL the command owns.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void narrowingResolvesMarksAndAdvisesTheMemberItRunsAndNoOther(@TempDir Path dir)
            throws Exception {
        Path stack = stackOf(dir);
        ForkedCli cli = ForkedCli.fork(dir, "dev", "--stack", stack.toString(), "--app-name",
                "alpha", "--port", "0", "--embedded-db", "--offline", "--watch");
        try {
            cli.awaitGatewayPort();
            // Printed after the hints, so the console is complete when it appears.
            cli.awaitLine("Watching every application's web/");
            String log = cli.log();

            assertThat(log).as("one runtime, at the member's own address").contains("/alpha/")
                    .doesNotContain("/beta/");
            assertThat(log).as("the idle member's module is not resolved")
                    .doesNotContain("tesseraql.modules artifact(s)");
            assertThat(log).as("the hint names the member that runs")
                    .contains("identity-schema --app " + stack.resolve("alpha"))
                    .doesNotContain("identity-schema --app " + stack.resolve("beta"));
            assertThat(stack.resolve("alpha/work/embedded-db.jdbc"))
                    .as("the marker a second terminal reads is left for the member that runs")
                    .exists();
            assertThat(stack.resolve("beta/work"))
                    .as("and the idle member's disk is untouched — no marker, no module cache")
                    .doesNotExist();
        } finally {
            cli.kill();
        }
    }

    /**
     * A name the stack does not hold is the gateway's refusal, before the database starts or any
     * member is touched — where it used to come after both.
     */
    @Test
    void anUnknownNameIsRefusedBeforeAnythingStarts(@TempDir Path dir) throws Exception {
        Path stack = stackOf(dir);
        ForkedCli cli = ForkedCli.fork(dir, "dev", "--stack", stack.toString(), "--app-name",
                "gamma", "--port", "0", "--embedded-db", "--offline");
        try {
            int exit = cli.awaitExit();
            String log = cli.log();

            assertThat(exit).as("refused.%n%s", log).isEqualTo(2);
            assertThat(log).contains(
                    "The stack holds no application named 'gamma'. It holds: alpha, beta.");
            assertThat(log).as("nothing started first")
                    .doesNotContain("Embedded PostgreSQL")
                    .doesNotContain("tesseraql.modules artifact(s)");
            assertThat(stack.resolve("alpha/work")).doesNotExist();
            assertThat(stack.resolve("beta/work")).doesNotExist();
        } finally {
            cli.kill();
        }
    }

    /** Two scaffolded members; {@code beta} declares a module and is the one the run leaves idle. */
    private static Path stackOf(Path dir) throws IOException {
        Path stack = Files.createDirectories(dir.resolve("stack"));
        Files.writeString(stack.resolve("tesseraql-stack.yml"), "# a stack for two test apps\n");
        AppScaffolder scaffolder = new AppScaffolder();
        for (String name : new String[]{"alpha", "beta"}) {
            scaffolder.writeNew(stack.resolve(name), scaffolder.scaffold(name));
        }
        Path betaConfig = stack.resolve("beta/config/tesseraql.yml");
        Files.writeString(betaConfig,
                ModulesYaml.addModule(Files.readString(betaConfig), PICOCLI));
        return stack;
    }
}
