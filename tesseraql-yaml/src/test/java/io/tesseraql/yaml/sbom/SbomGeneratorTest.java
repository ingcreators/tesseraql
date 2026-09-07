package io.tesseraql.yaml.sbom;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.sbom.SbomGenerator.MavenComponent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class SbomGeneratorTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    private static final List<MavenComponent> DEPENDENCIES = List.of(
            new MavenComponent("org.postgresql", "postgresql", "42.7.4", "ab12".repeat(16),
                    List.of("BSD-2-Clause")),
            new MavenComponent("io.vertx", "vertx-core", "4.18.0", null, List.of()));

    @Test
    void dependenciesBecomeLibraryComponentsWithPurlHashAndLicense() {
        String sbom = new SbomGenerator().toJson(exampleApp(), "user-admin", "1.0.0",
                DEPENDENCIES);

        assertThat(sbom)
                .contains("\"purl\" : \"pkg:maven/org.postgresql/postgresql@42.7.4\"")
                .contains("\"purl\" : \"pkg:maven/io.vertx/vertx-core@4.18.0\"")
                .contains("\"name\" : \"BSD-2-Clause\"")
                .contains("\"type\" : \"library\"")
                // Source file components are still present alongside the libraries.
                .contains("config/tesseraql.yml");
    }

    /**
     * The purl sort, which is what makes the component list independent of the order Maven
     * resolved the dependencies in. This says nothing about salted iteration order — both calls
     * share one JVM and therefore one salt; {@link io.tesseraql.yaml.release.ReleaseDocumentOrderTest}
     * guards that.
     */
    @Test
    void dependenciesAreOrderedByPurl() {
        SbomGenerator generator = new SbomGenerator();
        String forward = generator.toJson(exampleApp(), "a", "1", DEPENDENCIES);
        String reversed = generator.toJson(exampleApp(), "a", "1",
                List.of(DEPENDENCIES.get(1), DEPENDENCIES.get(0)));
        assertThat(forward).isEqualTo(reversed);
        // io.vertx sorts before org.postgresql. The assertion this replaces named camel-core,
        // which the fixture has not carried since the Camel removal campaign — indexOf returned
        // -1 and the comparison passed for the wrong reason.
        assertThat(forward.indexOf("vertx-core")).isLessThan(forward.indexOf("postgresql"));
    }
}
