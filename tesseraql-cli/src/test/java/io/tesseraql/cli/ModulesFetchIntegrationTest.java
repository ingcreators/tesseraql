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

    /** A tiny, stable closure — the test is about the bag, not about what is in it. */
    private static final String MODULE = "org.slf4j:slf4j-api:2.0.17";

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

        assertThat(new CommandLine(new TesseraqlCli())
                .execute("modules", "fetch", "--app", app.toString(), "--into", bag.toString()))
                .isZero();

        assertThat(bag.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar")).exists();
        assertThat(bag.resolve("bag.json")).exists();
        assertThat(Files.readString(bag.resolve("bag.json")))
                .contains("org.slf4j:slf4j-api:2.0.17")
                .contains("\"source\" : \"orders\"");

        // The disconnected side: the bag is the only repository, and nothing may leave the machine.
        Path deployed = writeApplication(dir.resolve("deployed"));
        Files.copy(app.resolve("modules.lock"), deployed.resolve("modules.lock"));
        assertThat(new CommandLine(new TesseraqlCli()).execute("modules", "resolve",
                "--app", deployed.toString(), "--repo", bag.toString(), "--offline")).isZero();
        assertThat(deployed.resolve("work/modules/slf4j-api-2.0.17.jar")).exists();
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
     * Copies the reactor's own BOM and the parent it inherits from into the bag, so a SNAPSHOT
     * build has them to import. A released version resolves both from Central like any other
     * artifact — which is also why a bag built by resolving contains them without being told to.
     */
    private static void seedFrameworkPoms(Path bag) throws Exception {
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        String version = io.tesseraql.core.TesseraqlVersion.current();
        for (String artifact : new String[]{"tesseraql-bom", "tesseraql-parent"}) {
            Path source = localRepo.resolve("io/tesseraql").resolve(artifact).resolve(version);
            if (!Files.isDirectory(source)) {
                continue;
            }
            Path target = bag.resolve("io/tesseraql").resolve(artifact).resolve(version);
            Files.createDirectories(target);
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : files.sorted(Comparator.naturalOrder()).toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, target.resolve(file.getFileName()));
                    }
                }
            }
        }
    }
}
