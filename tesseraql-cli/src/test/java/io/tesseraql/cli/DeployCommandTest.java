package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.operations.app.InstalledApp;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The {@code deploy} verb writes the install root's state exactly as {@code AppUpgrader} writes
 * it (docs/runtime-replace.md structural decision 3): a running host's reconciler converges to
 * the files, and with no host running the next start does — so these tests assert on the files
 * and on the exit codes, never on a runtime.
 */
class DeployCommandTest {

    @Test
    void aDirectoryWithoutACatalogueIsRefusedNamingTheInstallRoot(@TempDir Path dir)
            throws Exception {
        Result result = execute("deploy", pkg(dir, "orders", "1.0.0").toString(),
                "--stack", dir.resolve("workspace").toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("TQL-UPGRADE-4092")
                .contains("holds no catalog.json")
                .contains("restarting the stack");
    }

    @Test
    void aMissingPackagePathIsAOneLineRefusal(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", dir.resolve("nope.tqlapp").toString(),
                "--stack", root.toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("No such package");
    }

    @Test
    void aDirectDeployMovesTheCatalogue(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Deployed 'orders' 2.0.0 (was 1.0.0)");
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
    }

    @Test
    void aPreflightRefusalSurfacesWithExitTwoAndWritesNothing(@TempDir Path dir)
            throws Exception {
        Path root = installRoot(dir, "orders", "2.0.0");
        Result result = execute("deploy", pkg(dir, "orders", "1.0.0").toString(),
                "--stack", root.toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("not newer");
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
    }

    @Test
    void aTamperedPackageIsRejectedBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString(), "--sha256", "0".repeat(64));
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("integrity");
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
    }

    @Test
    void theCanaryLifecycleRidesTheStateFile(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");

        Result staged = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString(), "--canary", "--weight", "25");
        assertThat(staged.exitCode()).isZero();
        assertThat(staged.stdout()).contains("Staged 'orders' 2.0.0 as a canary at 25%");
        // The catalogue is untouched while the candidate is staged.
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");

        Result status = execute("deploy", "status", "--stack", root.toString());
        assertThat(status.stdout()).contains("orders: active 1.0.0")
                .contains("canary: 2.0.0 at 25%");

        Result weight = execute("deploy", "weight", "orders", "50", "--stack", root.toString());
        assertThat(weight.exitCode()).isZero();
        assertThat(execute("deploy", "status", "orders", "--stack", root.toString()).stdout())
                .contains("canary: 2.0.0 at 50%");

        Result promoted = execute("deploy", "promote", "orders", "--stack", root.toString());
        assertThat(promoted.exitCode()).isZero();
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");

        Result rolledBack = execute("deploy", "rollback", "orders", "--stack", root.toString());
        assertThat(rolledBack.exitCode()).isZero();
        assertThat(rolledBack.stdout()).contains("Rolled back 'orders' to 1.0.0");
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
    }

    @Test
    void rollingBackAStagedCanaryDiscardsIt(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        execute("deploy", pkg(dir, "orders", "2.0.0").toString(), "--stack", root.toString(),
                "--canary");

        Result discarded = execute("deploy", "rollback", "orders", "--stack", root.toString());
        assertThat(discarded.exitCode()).isZero();
        assertThat(discarded.stdout()).contains("Discarded the staged candidate");
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
    }

    @Test
    void weightWithoutACanaryIsRefusedByTheLibrary(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", "weight", "orders", "50", "--stack", root.toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("No staged candidate");
    }

    @Test
    void weightOnTheDeployItselfNeedsCanary(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString(), "--weight", "25");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("--canary");
    }

    @Test
    void statusRendersTheHostsReportBesideTheIntent(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Files.writeString(root.resolve(".upgrade").resolve("orders.status.json"),
                "{\"name\":\"orders\",\"action\":\"replace\",\"version\":\"1.0.0\","
                        + "\"outcome\":\"applied\",\"message\":null,"
                        + "\"at\":\"2026-08-18T00:00:00Z\"}");
        Result result = execute("deploy", "status", "orders", "--stack", root.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("orders: active 1.0.0")
                .contains("host: applied replace v1.0.0 at 2026-08-18T00:00:00Z");
    }

    @Test
    void statusRefusesAnUnknownName(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", "status", "billing", "--stack", root.toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("no 'billing'");
    }

    @Test
    void waitReturnsWhenTheHostReports(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Path status = root.resolve(".upgrade").resolve("orders.status.json");
        Thread host = new Thread(() -> {
            try {
                Thread.sleep(600);
                Files.writeString(status, "{\"name\":\"orders\",\"action\":\"replace\","
                        + "\"version\":\"2.0.0\",\"outcome\":\"applied\",\"message\":null,"
                        + "\"at\":\"2026-08-18T00:00:00Z\"}");
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        host.start();
        Result result = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString(), "--wait", "--wait-timeout", "10");
        host.join();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("The host applied it: replace v2.0.0");
    }

    @Test
    void waitSurfacesARefusalWithExitTwo(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Path status = root.resolve(".upgrade").resolve("orders.status.json");
        Thread host = new Thread(() -> {
            try {
                Thread.sleep(600);
                Files.writeString(status, "{\"name\":\"orders\",\"action\":null,"
                        + "\"version\":null,\"outcome\":\"refused\","
                        + "\"message\":\"TQL-APP-4216: unresolved modules\","
                        + "\"at\":\"2026-08-18T00:00:00Z\"}");
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        host.start();
        Result result = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString(), "--wait", "--wait-timeout", "10");
        host.join();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("The host refused it")
                .contains("unresolved modules");
    }

    @Test
    void waitTimesOutLoudlyAndTheIntentStaysWritten(@TempDir Path dir) throws Exception {
        Path root = installRoot(dir, "orders", "1.0.0");
        Result result = execute("deploy", pkg(dir, "orders", "2.0.0").toString(),
                "--stack", root.toString(), "--wait", "--wait-timeout", "1");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("Timed out after 1s")
                .contains("orders.status.json");
        // The timeout is "the host has not answered", not "the deploy failed".
        assertThat(new AppCatalog(root).find("orders")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
    }

    private static Path installRoot(Path dir, String name, String version) throws Exception {
        Path root = dir.resolve("apps");
        new AppInstaller().install(pkg(dir, name, version), root);
        Files.createDirectories(root.resolve(".upgrade"));
        return root;
    }

    private static Path pkg(Path dir, String name, String version) throws Exception {
        Path output = Files.createTempFile(dir, name + "-" + version, ".tqlapp");
        String yaml = "tesseraql:\n  app:\n    name: " + name + "\n    version: " + version
                + "\n    requires:\n      framework: \"*\"\n";
        try (OutputStream out = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("config/tesseraql.yml"));
            zip.write(yaml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output;
    }

    private static Result execute(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new TesseraqlCli()).execute(args);
            return new Result(exitCode, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
