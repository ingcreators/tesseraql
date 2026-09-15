package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resolved closure of a module is the module and what it brings, never the framework it was
 * compiled against (docs/codec-discovery.md S5): the runtime that loads the module already carries
 * {@code io.tesseraql:*}, parent-first, so a framework jar in {@code work/modules} never loads and a
 * lock naming it claims a version that does not run.
 *
 * <p>Offline against a repository this test writes, in which the framework artifact <em>is</em>
 * resolvable — so what the assertion sees is the exclusion, not an absence — and the module's own
 * third-party dependency is too, so the same assertion sees that transitivity survived.
 */
class ModuleResolverTest {

    private static final String MODULE = "io.tesseraql:tesseraql-fake-codec:9.9.9";
    private static final String FRAMEWORK = "io.tesseraql:tesseraql-core:9.9.9";
    private static final String ENGINE = "io.example:engine:1.0.0";

    private String previousLocalRepo;

    @BeforeEach
    void rememberLocalRepository() {
        previousLocalRepo = System.getProperty("maven.repo.local");
    }

    @AfterEach
    void restoreLocalRepository() {
        if (previousLocalRepo == null) {
            System.clearProperty("maven.repo.local");
        } else {
            System.setProperty("maven.repo.local", previousLocalRepo);
        }
    }

    @Test
    void aModulesClosureKeepsWhatItBringsAndDropsTheFrameworkItCompiledAgainst(@TempDir Path dir)
            throws Exception {
        Path repository = dir.resolve("repository");
        install(repository, FRAMEWORK, List.of());
        install(repository, ENGINE, List.of());
        install(repository, MODULE, List.of(FRAMEWORK, ENGINE));
        System.setProperty("maven.repo.local", repository.toAbsolutePath().toString());

        List<ResolvedModule> resolved = new ModuleResolver("io.tesseraql:tesseraql-bom:0.0.0-none",
                true).resolve(List.of(ModuleCoordinate.parse(MODULE)));

        assertThat(resolved).extracting(ResolvedModule::coordinate)
                .as("the module and its engine, and not the framework jar the runtime carries")
                .containsExactly(ENGINE, MODULE);
    }

    /** One artifact into a local repository: its POM naming {@code dependencies}, and a jar. */
    private static void install(Path repository, String coordinate, List<String> dependencies)
            throws IOException {
        String[] gav = coordinate.split(":");
        Path home = repository.resolve(gav[0].replace('.', '/')).resolve(gav[1]).resolve(gav[2]);
        Files.createDirectories(home);
        StringBuilder declared = new StringBuilder();
        for (String dependency : dependencies) {
            String[] dep = dependency.split(":");
            declared.append("    <dependency><groupId>").append(dep[0])
                    .append("</groupId><artifactId>").append(dep[1])
                    .append("</artifactId><version>").append(dep[2])
                    .append("</version></dependency>\n");
        }
        Files.writeString(home.resolve(gav[1] + "-" + gav[2] + ".pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(gav[0], gav[1], gav[2], declared));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(home.resolve(gav[1] + "-" + gav[2] + ".jar")), manifest)) {
            jar.flush();
        }
    }
}
