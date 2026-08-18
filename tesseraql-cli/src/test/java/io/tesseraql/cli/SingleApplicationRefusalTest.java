package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The single-application commands — {@code package}, {@code scaffold}, {@code release-diff},
 * {@code verify} — refuse a directory holding several applications and print the commands that
 * would have worked (docs/cli-surface.md Decision 2): a refusal that names the alternatives costs
 * a second, one that says "expected a single application" costs a directory listing and a guess.
 */
class SingleApplicationRefusalTest {

    @Test
    void packageRefusesAFolderOfApplicationsNamingEach(@TempDir Path dir) {
        Path stack = twoApplications(dir);
        Refusal refusal = execute("package", "--app", stack.toString());
        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.stderr()).contains("is not an application; it holds 2.")
                .contains("tesseraql package --app " + stack.resolve("billing"))
                .contains("tesseraql package --app " + stack.resolve("orders"));
    }

    @Test
    void verifyRefusesBeforeTouchingTheEvidence(@TempDir Path dir) {
        Path stack = twoApplications(dir);
        // The evidence file does not exist: the shape refusal must come first, so the operator
        // fixes the actual mistake instead of chasing a missing file.
        Refusal refusal = execute("verify", "--app", stack.toString(),
                "--evidence-file", stack.resolve("release-evidence.json").toString());
        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.stderr()).contains("tesseraql verify --app");
    }

    @Test
    void releaseDiffGuardsBothTrees(@TempDir Path dir) {
        Path stack = twoApplications(dir);
        Path candidate = stack.resolve("orders");

        Refusal candidateRefused = execute("release-diff", "--app", stack.toString(),
                "--baseline", candidate.toString());
        assertThat(candidateRefused.exitCode()).isEqualTo(2);
        assertThat(candidateRefused.stderr()).contains("tesseraql release-diff --app");

        // The baseline is an application tree too — a folder of them diffed silently would
        // report every route as added.
        Refusal baselineRefused = execute("release-diff", "--app", candidate.toString(),
                "--baseline", stack.toString());
        assertThat(baselineRefused.exitCode()).isEqualTo(2);
        assertThat(baselineRefused.stderr())
                .contains("tesseraql release-diff --baseline " + stack.resolve("orders"));
    }

    @Test
    void scaffoldSubcommandsRefuseTheWorkspaceShape(@TempDir Path dir) {
        Path stack = twoApplications(dir);
        Refusal crud = execute("scaffold", "crud", "--app", stack.toString(),
                "--table", "items");
        assertThat(crud.exitCode()).isEqualTo(2);
        assertThat(crud.stderr()).contains("tesseraql scaffold crud --app");

        Refusal decision = execute("scaffold", "decision", "--app", stack.toString(),
                "--name", "shippingFee", "--inputs", "weight:between", "--outputs", "fee");
        assertThat(decision.exitCode()).isEqualTo(2);
        assertThat(decision.stderr()).contains("tesseraql scaffold decision --app");

        Refusal eject = execute("scaffold", "eject-view", "--app", stack.toString(),
                "--route", "web/items/get.yml");
        assertThat(eject.exitCode()).isEqualTo(2);
        assertThat(eject.stderr()).contains("tesseraql scaffold eject-view --app");
    }

    @Test
    void aDirectoryHoldingNothingIsRefusedWithTheExpectedShapes(@TempDir Path dir) {
        Refusal refusal = execute("package", "--app", dir.toString());
        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.stderr()).contains("holds no application");
    }

    private static Path twoApplications(Path dir) {
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(dir.resolve("orders"), scaffolder.scaffold("orders"));
        scaffolder.writeNew(dir.resolve("billing"), scaffolder.scaffold("billing"));
        return dir;
    }

    private static Refusal execute(String... args) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new TesseraqlCli()).execute(args);
            return new Refusal(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }
    }

    private record Refusal(int exitCode, String stderr) {
    }
}
