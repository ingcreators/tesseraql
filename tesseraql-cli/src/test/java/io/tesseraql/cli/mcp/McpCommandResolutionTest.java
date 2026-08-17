package io.tesseraql.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.cli.TesseraqlCli;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code mcp} resolves what it serves the way {@code dev} does — {@code --stack} names the
 * directory holding the applications, {@code --app-name} narrows — and refuses the shapes that
 * would make it guess, printing the fix (docs/cli-surface.md decisions 2, 3 and 9). Only the
 * refusal paths run here; they return before any transport serves.
 */
class McpCommandResolutionTest {

    @Test
    void anApplicationHomeIsNotAStack(@TempDir Path dir) {
        Path app = dir.resolve("orders");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("orders"));

        Refusal refusal = execute("mcp", "--stack", app.toString());
        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.stderr()).contains("is one application, not a stack")
                .contains("--app-name orders");
    }

    @Test
    void narrowingToAStrangerListsWhatTheStackHolds(@TempDir Path dir) {
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(dir.resolve("orders"), scaffolder.scaffold("orders"));
        scaffolder.writeNew(dir.resolve("billing"), scaffolder.scaffold("billing"));

        Refusal refusal = execute("mcp", "--stack", dir.toString(), "--app-name", "shipping");
        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.stderr()).contains("no application named 'shipping'")
                .contains("orders").contains("billing");
    }

    @Test
    void aDirectoryHoldingNothingIsRefused(@TempDir Path dir) {
        Refusal refusal = execute("mcp", "--stack", dir.toString());
        assertThat(refusal.exitCode()).isEqualTo(2);
        assertThat(refusal.stderr()).contains("holds no application");
    }

    private static Refusal execute(String... args) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = new picocli.CommandLine(new TesseraqlCli()).execute(args);
            return new Refusal(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }
    }

    private record Refusal(int exitCode, String stderr) {
    }
}
