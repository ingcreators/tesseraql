package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher's first run prints what the command prints and nothing else on stdout
 * (docs/codec-discovery.md decision 8).
 *
 * <p>The run that writes the CDS archive reports every class it could not archive, at warning
 * level, on the JVM's default log output — stdout. The shipped {@code -Xlog:cds=error:stderr}
 * added a stderr output and left that default alone, so {@code tesseraql --version} on a fresh
 * cache printed 145 {@code [warning][cds] Skipping …} lines ahead of the version, on the stream
 * {@code routes --format json | jq} reads. The launchers now switch the default output off and
 * rebuild it on stderr.
 *
 * <p>The stub main creates a dynamic proxy: a proxy class has no archivable location, so the
 * writing run has exactly one class to report — enough to tell the two flag sets apart, where a
 * main that loads nothing unusual would print nothing under either.
 */
class QuietLauncherTest {

    private static final String MARKER = "LAUNCHER-OK";

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void thePosixLaunchersFirstRunPrintsOnlyTheCommandsOutput(@TempDir Path tmp)
            throws Exception {
        Path home = distribution(tmp, "tesseraql");

        Output first = run(tmp, List.of(home.resolve("bin/tesseraql").toString(), "--version"),
                Map.of("XDG_CACHE_HOME", tmp.resolve("cache").toString()));

        assertThat(first.stdout()).as("stdout on the run that writes the archive")
                .isEqualTo(MARKER + "\n");
        assertThat(first.stderr()).as("the skipped class is not an error either")
                .doesNotContain("[cds]");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theWindowsLaunchersFirstRunPrintsOnlyTheCommandsOutput(@TempDir Path tmp)
            throws Exception {
        Path home = distribution(tmp, "tesseraql.cmd");

        Output first = run(tmp, List.of("cmd", "/c", home.resolve("bin/tesseraql.cmd").toString(),
                "--version"), Map.of("LOCALAPPDATA", tmp.resolve("cache").toString()));

        assertThat(first.stdout().strip()).as("stdout on the run that writes the archive")
                .isEqualTo(MARKER);
        assertThat(first.stderr()).doesNotContain("[cds]");
    }

    /** The real launcher over a stub main whose archive dump has a class to skip. */
    private static Path distribution(Path tmp, String launcherName) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("dist"));
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Path launcher = home.resolve("bin").resolve(launcherName);
        Files.copy(Path.of("src/main/dist/bin", launcherName), launcher);
        launcher.toFile().setExecutable(true);

        Path src = Files.createDirectories(tmp.resolve("src/io/tesseraql/cli"));
        Files.writeString(src.resolve("TesseraqlCli.java"), """
                package io.tesseraql.cli;
                public final class TesseraqlCli {
                    public static void main(String[] args) {
                        Runnable proxy = (Runnable) java.lang.reflect.Proxy.newProxyInstance(
                                TesseraqlCli.class.getClassLoader(),
                                new Class<?>[] {Runnable.class}, (p, m, a) -> null);
                        proxy.run();
                        System.out.println("%s");
                    }
                }
                """.formatted(MARKER));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), src.resolve("TesseraqlCli.java").toString());
        assertThat(compiled).as("the stub main class compiled").isZero();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(home.resolve("lib/tesseraql.jar")), manifest)) {
            jar.putNextEntry(new ZipEntry("io/tesseraql/cli/TesseraqlCli.class"));
            jar.write(Files.readAllBytes(classes.resolve("io/tesseraql/cli/TesseraqlCli.class")));
            jar.closeEntry();
        }
        return home;
    }

    private record Output(String stdout, String stderr) {
    }

    /** Runs a command with the given environment additions, the two streams kept apart. */
    private static Output run(Path tmp, List<String> command, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
                .directory(tmp.toFile())
                .redirectOutput(tmp.resolve("stdout.txt").toFile())
                .redirectError(tmp.resolve("stderr.txt").toFile());
        builder.environment().putAll(environment);
        // The container's JAVA_TOOL_OPTIONS makes the JVM announce itself on stderr; not
        // this test's subject.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = builder.start();
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the launcher exited").isTrue();
        return new Output(Files.readString(tmp.resolve("stdout.txt"), StandardCharsets.UTF_8),
                Files.readString(tmp.resolve("stderr.txt"), StandardCharsets.UTF_8));
    }
}
