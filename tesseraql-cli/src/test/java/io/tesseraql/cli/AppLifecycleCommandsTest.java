package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.release.ReleaseEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The database-free app-lifecycle CLI surface — {@code lint}, {@code generate}, {@code governance},
 * {@code package} and {@code verify} — drives the same engines as the Maven goals over a freshly
 * scaffolded app.
 */
class AppLifecycleCommandsTest {

    @Test
    void lintPassesOnAFreshlyScaffoldedApp(@TempDir Path dir) {
        Path app = scaffold(dir);
        assertThat(execute("lint", "--app", app.toString())).isZero();
    }

    @Test
    void generateWritesOpenApiHtmxAndDocsSpec(@TempDir Path dir) {
        Path app = scaffold(dir);
        assertThat(execute("generate", "--app", app.toString())).isZero();
        assertThat(app.resolve("work/generated/openapi.json")).exists();
        assertThat(app.resolve("work/generated/htmx-contract.json")).exists();
        assertThat(app.resolve("work/generated/docs/spec.json")).exists();
    }

    @Test
    void governanceAssessesRoutesWithoutFailingWhenAsked(@TempDir Path dir) {
        Path app = scaffold(dir);
        assertThat(execute("governance", "--app", app.toString(), "--no-fail-on-violation"))
                .isZero();
    }

    @Test
    void packageProducesADeterministicArchiveWithAChecksum(@TempDir Path dir) throws Exception {
        Path app = scaffold(dir);
        assertThat(execute("package", "--app", app.toString())).isZero();
        Path archive = app.resolve("work/" + app.getFileName() + ".tqlapp");
        assertThat(archive).exists();
        assertThat(app.resolve("work/" + app.getFileName() + ".tqlapp.sha256")).exists();

        byte[] first = Files.readAllBytes(archive);
        assertThat(execute("package", "--app", app.toString())).isZero();
        assertThat(Files.readAllBytes(archive)).isEqualTo(first);
    }

    @Test
    void verifyAcceptsMatchingEvidenceAndRejectsTamperedSources(@TempDir Path dir)
            throws Exception {
        Path app = scaffold(dir);
        Path evidence = dir.resolve("release-evidence.json");
        Files.writeString(evidence, new ReleaseEvidence()
                .toJson(new ManifestLoader().load(app), "demo", "1.0.0"));

        assertThat(execute("verify", "--app", app.toString(),
                "--evidence-file", evidence.toString())).isZero();

        // Tampering with a recorded source breaks the recorded hash.
        Path tampered = app.resolve("config/tesseraql.yml");
        Files.writeString(tampered, Files.readString(tampered) + "\n# tampered\n");
        assertThat(execute("verify", "--app", app.toString(),
                "--evidence-file", evidence.toString())).isEqualTo(1);
    }

    private static Path scaffold(Path dir) {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        return dir.resolve("demo");
    }

    private static int execute(String... args) {
        return new CommandLine(new TesseraqlCli()).execute(args);
    }
}
