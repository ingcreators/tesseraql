package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The bag, end to end (docs/module-channel.md decision 5): {@code modules fetch} fills a local
 * Maven repository on a connected machine, and the disconnected side resolves out of it with
 * {@code --repo … --offline} — nothing else configured, no network reached.
 *
 * <p>What this proves is the mechanism the design rests on: the bag is <em>resolved into</em>, so
 * it carries the poms and metadata an offline resolution checks. A directory of copied jars looks
 * the same to a reader and fails here.
 */
class ModulesFetchIntegrationTest {

    /**
     * A tiny, stable closure — the test is about the bag, not about what is in it. The version is
     * the reactor's own {@code slf4j.version}, so the artifact already sits in the local
     * repository of any machine that built the project (a pinned foreign version re-downloaded
     * from Central on every run, and two consecutive Windows CI runs failed on Central
     * throttling).
     */
    private static final String SLF4J_VERSION = reactorSlf4jVersion();

    private static final String MODULE = "org.slf4j:slf4j-api:" + SLF4J_VERSION;

    private String previousLocalRepo;

    @AfterEach
    void restoreLocalRepository() {
        if (previousLocalRepo == null) {
            System.clearProperty("maven.repo.local");
        } else {
            System.setProperty("maven.repo.local", previousLocalRepo);
        }
    }

    @Test
    void aFetchedBagResolvesOffline(@TempDir Path dir) throws Exception {
        previousLocalRepo = System.getProperty("maven.repo.local");
        Path app = writeApplication(dir.resolve("orders"));
        Path bag = dir.resolve("bag");

        // The lock is written first, exactly as a developer does before packaging: fetch collects
        // the reviewed closure, and refuses an application that has no lock to review.
        assertThat(new CommandLine(new TesseraqlCli())
                .execute("modules", "resolve", "--app", app.toString())).isZero();

        // A SNAPSHOT BOM lives only in the developer's own repository; a released one comes from
        // Central like everything else. Seed it so the fetch's BOM import has somewhere to find it.
        seedFrameworkPoms(bag);
        // The fetch resolves into the bag (maven.repo.local points there), so it never consults
        // ~/.m2 on its own — seed the module from the local repository the build just filled, and
        // the fetch proves the bag mechanism without depending on Central being reachable.
        seedModuleFromLocalRepository(bag);

        assertThat(new CommandLine(new TesseraqlCli())
                .execute("modules", "fetch", "--app", app.toString(), "--into", bag.toString()))
                .isZero();

        assertThat(bag.resolve("org/slf4j/slf4j-api/" + SLF4J_VERSION
                + "/slf4j-api-" + SLF4J_VERSION + ".jar")).exists();
        assertThat(bag.resolve("bag.json")).exists();
        assertThat(Files.readString(bag.resolve("bag.json")))
                .contains(MODULE)
                .contains("\"source\" : \"orders\"");

        // The disconnected side: the bag is the only repository, and nothing may leave the machine.
        Path deployed = writeApplication(dir.resolve("deployed"));
        Files.copy(app.resolve("modules.lock"), deployed.resolve("modules.lock"));
        assertThat(new CommandLine(new TesseraqlCli()).execute("modules", "resolve",
                "--app", deployed.toString(), "--repo", bag.toString(), "--offline")).isZero();
        assertThat(deployed.resolve("work/modules/slf4j-api-" + SLF4J_VERSION + ".jar")).exists();
    }

    /** The reactor's {@code slf4j.version}, read from the parent POM the test already leans on. */
    private static String reactorSlf4jVersion() {
        Path parentPom = Path.of("..", "pom.xml").toAbsolutePath().normalize();
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<slf4j\\.version>([^<]+)</slf4j\\.version>")
                    .matcher(Files.readString(parentPom));
            if (!matcher.find()) {
                throw new IllegalStateException("No slf4j.version property in " + parentPom);
            }
            return matcher.group(1);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /**
     * Seeds the bag with the declared module from the machine's local repository, when it is
     * there — which it is on any machine that built the project, the reactor depending on the
     * same version. Every {@code org.slf4j} artifact at that version is copied, so the parent
     * POM chain the resolution reads (slf4j-api → slf4j-parent → slf4j-bom) comes along without
     * this test pinning slf4j's internal structure. When the local repository has none of it,
     * the fetch downloads from Central exactly as before; the seed only removes the network
     * from the common case, it never fails the test.
     */
    private static void seedModuleFromLocalRepository(Path bag) throws Exception {
        Path localSlf4j = Path.of(System.getProperty("user.home"), ".m2", "repository")
                .resolve("org/slf4j");
        if (!Files.isDirectory(localSlf4j)) {
            return;
        }
        try (Stream<Path> artifacts = Files.list(localSlf4j)) {
            for (Path artifact : artifacts.sorted(Comparator.naturalOrder()).toList()) {
                Path installed = artifact.resolve(SLF4J_VERSION);
                if (!Files.isDirectory(installed)) {
                    continue;
                }
                Path target = bag.resolve("org/slf4j").resolve(artifact.getFileName())
                        .resolve(SLF4J_VERSION);
                Files.createDirectories(target);
                try (Stream<Path> files = Files.list(installed)) {
                    for (Path file : files.sorted(Comparator.naturalOrder()).toList()) {
                        if (Files.isRegularFile(file)) {
                            Files.copy(file, target.resolve(file.getFileName()));
                        }
                    }
                }
            }
        }
    }

    /** An application home declaring one module and nothing else of consequence. */
    private static Path writeApplication(Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: orders
                  modules:
                    - %s
                """.formatted(MODULE));
        return home;
    }

    /**
     * Seeds the bag with the framework's own BOM and the parent it inherits from, so the fetch's
     * BOM import has somewhere to find them. A released version resolves both from Central like
     * any other artifact — which is also why a bag built by resolving contains them without being
     * told to. A SNAPSHOT build has them only in the developer's local repository, and on CI
     * (which runs {@code verify}, not {@code install}) not even there, so the reactor's own POM
     * files are the fallback.
     */
    private static void seedFrameworkPoms(Path bag) throws Exception {
        String version = io.tesseraql.core.TesseraqlVersion.current();
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        seed(bag, "tesseraql-bom", version,
                localRepo.resolve("io/tesseraql/tesseraql-bom").resolve(version),
                Path.of("..", "tesseraql-bom", "pom.xml"));
        seed(bag, "tesseraql-parent", version,
                localRepo.resolve("io/tesseraql/tesseraql-parent").resolve(version),
                Path.of("..", "pom.xml"));
    }

    /** One artifact into the bag: the installed directory when it exists, else the source POM. */
    private static void seed(Path bag, String artifact, String version, Path installed,
            Path sourcePom) throws Exception {
        Path target = bag.resolve("io/tesseraql").resolve(artifact).resolve(version);
        Files.createDirectories(target);
        if (Files.isDirectory(installed)) {
            try (Stream<Path> files = Files.list(installed)) {
                for (Path file : files.sorted(Comparator.naturalOrder()).toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, target.resolve(file.getFileName()));
                    }
                }
            }
            return;
        }
        Files.copy(sourcePom.toAbsolutePath().normalize(),
                target.resolve(artifact + "-" + version + ".pom"));
    }
}
