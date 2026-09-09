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
 * The shipped launchers survive a space in the path they cache a CDS archive under.
 *
 * <p>Both build that path into the JVM option string unquoted and then expand the string
 * unquoted, so one space splits the option into two arguments. The second is not an option, so
 * the JVM launcher takes it as the main class and the CLI dies before running any of its own
 * code — and because the stray token arrives before {@code -cp}, the real classpath and main
 * class are demoted to program arguments too. The failure is total, not degraded.
 *
 * <p>On Windows the path is {@code %LOCALAPPDATA%}, which contains a space for every user whose
 * profile name does; on POSIX it is {@code $XDG_CACHE_HOME} or {@code $HOME/.cache}. Nothing
 * executed either launcher against such a path: {@code ci.yml}'s distribution job runs the POSIX
 * one from {@code $RUNNER_TEMP}, and no job runs the {@code .cmd} at all.
 *
 * <p>Each case builds a minimal distribution around the real launcher — {@code bin/} beside a
 * {@code lib/tesseraql.jar} whose main class is the one the launcher names — so a successful run
 * prints a marker. That makes the assertion unambiguous: without the fix the JVM reports a main
 * class taken from the middle of the cache path, with it the stub runs.
 */
class SpacedPathLauncherTest {

    private static final String MARKER = "LAUNCHER-OK";
    private static final String SPACED = "cache dir with space";

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void thePosixLauncherRunsWhenTheCacheDirectoryHasASpace(@TempDir Path tmp) throws Exception {
        Path home = distribution(tmp, "tesseraql");

        assertThat(run(tmp, List.of(home.resolve("bin/tesseraql").toString(), "--version"),
                Map.of("XDG_CACHE_HOME", tmp.resolve("plain").toString())))
                .as("control: no space in the cache path")
                .contains(MARKER);

        assertThat(run(tmp, List.of(home.resolve("bin/tesseraql").toString(), "--version"),
                Map.of("XDG_CACHE_HOME", tmp.resolve(SPACED).toString())))
                .as("the launcher must not hand the JVM half of its own cache path")
                .contains(MARKER);
    }

    /**
     * The CDS archive's name has to keep tracking the classpath when the installation directory
     * contains a space.
     *
     * <p>This one never failed to launch, which is why it needs its own case: splitting the
     * classpath on whitespace as well as on its separator made the size-and-name listing find
     * nothing, so the fingerprint became the checksum of an empty input — one constant for every
     * classpath. Adding an extension jar then reused the previous archive, the JVM found one that
     * no longer matched, and — as the launcher's own comment says — quietly stopped using it. The
     * measured start-up saving was lost permanently and with nothing printed.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theArchiveNameStillTracksTheClasspathUnderASpacedInstallation(@TempDir Path tmp)
            throws Exception {
        Path home = distribution(tmp.resolve("install dir with space"), "tesseraql");
        Path launcher = home.resolve("bin/tesseraql");

        Path before = tmp.resolve("cache-before");
        assertThat(run(tmp, List.of(launcher.toString(), "--version"),
                Map.of("XDG_CACHE_HOME", before.toString()))).contains(MARKER);

        Files.createDirectories(home.resolve("lib/ext"));
        Files.copy(home.resolve("lib/tesseraql.jar"), home.resolve("lib/ext/extra.jar"));

        Path after = tmp.resolve("cache-after");
        assertThat(run(tmp, List.of(launcher.toString(), "--version"),
                Map.of("XDG_CACHE_HOME", after.toString()))).contains(MARKER);

        assertThat(archiveNames(after))
                .as("adding an extension jar must land on a different archive")
                .isNotEmpty()
                .isNotEqualTo(archiveNames(before));
    }

    /** The CDS archive file names the launcher left in a cache directory. */
    private static List<String> archiveNames(Path cache) throws IOException {
        Path dir = cache.resolve("tesseraql");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".jsa"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theWindowsLauncherRunsWhenTheProfilePathHasASpace(@TempDir Path tmp) throws Exception {
        Path home = distribution(tmp, "tesseraql.cmd");
        String launcher = home.resolve("bin/tesseraql.cmd").toString();

        assertThat(run(tmp, List.of("cmd", "/c", launcher, "--version"),
                Map.of("LOCALAPPDATA", tmp.resolve("plain").toString())))
                .as("control: no space in the profile path")
                .contains(MARKER);

        assertThat(run(tmp, List.of("cmd", "/c", launcher, "--version"),
                Map.of("LOCALAPPDATA", tmp.resolve(SPACED).toString())))
                .as("the launcher must not hand the JVM half of its own cache path")
                .contains(MARKER);
    }

    /**
     * A distribution layout around the real launcher: {@code bin/<name>} copied from
     * {@code src/main/dist}, and a {@code lib/tesseraql.jar} holding a stub whose fully qualified
     * name is the one the launcher execs. The stub is what makes success observable — the real fat
     * jar is not built in the {@code test} phase.
     */
    private static Path distribution(Path tmp, String launcherName) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("dist"));
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));

        Path source = Path.of("src/main/dist/bin", launcherName);
        Path launcher = home.resolve("bin").resolve(launcherName);
        Files.copy(source, launcher);
        launcher.toFile().setExecutable(true);

        Path src = Files.createDirectories(tmp.resolve("src/io/tesseraql/cli"));
        Files.writeString(src.resolve("TesseraqlCli.java"), """
                package io.tesseraql.cli;
                public final class TesseraqlCli {
                    public static void main(String[] args) {
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

    /** Runs a command with the given environment additions and returns its merged output. */
    private static String run(Path tmp, List<String> command, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
                .directory(tmp.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the launcher exited").isTrue();
        return output;
    }
}
