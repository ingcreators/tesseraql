package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The deployment distribution's contract (docs/runtime-footprint.md decision 1). These tests run
 * on the host module's own classpath — the exclusions applied — so building the command model and
 * executing a verb here is what proves the operator commands load and run without the workshop,
 * the embedded-database supervisor, or the artifact-resolver stack.
 */
class TesseraqlHostCliTest {

    @Test
    void theRosterIsExactlyTheOperatorVerbs() {
        // Building the model instantiates every listed command class on this reduced classpath.
        CommandLine host = TesseraqlHostCli.commandLine();
        assertThat(host.getSubcommands().keySet()).containsExactlyInAnyOrder(
                "host", "deploy", "routes", "token", "migrate", "job", "identity-schema",
                "verify", "admission", "duckdb");
    }

    /**
     * One exit-code contract for both binaries (docs/cli-surface.md decision 10): the host
     * declares the one list ({@code ExitCodes}; the developer CLI's model cannot be built on
     * this reduced classpath, so the constants are the comparison), and a refusal here is one
     * line and exit 2 through the same shaper.
     */
    @Test
    void theExitCodesAreTheDeveloperClisAndARefusalIsOneLine() {
        CommandLine host = TesseraqlHostCli.commandLine();
        java.util.Map<String, String> declared = new java.util.LinkedHashMap<>();
        for (String entry : java.util.List.of(ExitCodes.OK, ExitCodes.FAILED,
                ExitCodes.REFUSED, ExitCodes.SKIPPED)) {
            declared.put(entry.substring(0, entry.indexOf(':')),
                    entry.substring(entry.indexOf(':') + 1));
        }
        assertThat(host.getCommandSpec().usageMessage().exitCodeList())
                .containsExactlyEntriesOf(declared)
                .containsKeys("0", "1", "2", "3");

        java.io.StringWriter err = new java.io.StringWriter();
        host.setErr(new java.io.PrintWriter(err, true));
        int exit = host.execute("identity-schema", "--admin-login", "admin");
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString().strip().lines()).hasSize(1);
        assertThat(err.toString()).contains("--jdbc-url").doesNotContain("Exception");
    }

    @Test
    void helpRendersForEveryVerb() {
        CommandLine host = TesseraqlHostCli.commandLine();
        for (CommandLine sub : host.getSubcommands().values()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sub.usage(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .as("usage of %s", sub.getCommandName())
                    .isNotBlank();
        }
    }

    @Test
    void routesExecutesOnTheDeploymentClasspath(@TempDir Path dir) {
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            exitCode = TesseraqlHostCli.commandLine()
                    .execute("routes", "--app", app.toString());
        } finally {
            System.setOut(original);
        }

        assertThat(exitCode).isZero();
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("GET");
    }

    @Test
    void theWorkshopIsAbsentFromThisClasspath() {
        // The enforcer rule fails the build when a banned artifact enters the dependency graph;
        // this is the behavioral half, proving the classpath the tests above ran on is the
        // reduced one and not an accident of test scope.
        for (String cls : new String[]{
                "io.tesseraql.studio.StudioService",
                "io.zonky.test.db.postgres.embedded.EmbeddedPostgres",
                "org.jboss.shrinkwrap.resolver.api.maven.Maven",
                "com.icegreen.greenmail.util.GreenMail",
                "junit.framework.TestCase"}) {
            assertThatThrownBy(() -> Class.forName(cls))
                    .as("%s must not be on the deployment classpath", cls)
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }
}
