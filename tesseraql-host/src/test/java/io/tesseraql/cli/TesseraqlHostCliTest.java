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
