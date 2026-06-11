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
            new MavenComponent("org.apache.camel", "camel-core", "4.18.0", null, List.of()));

    @Test
    void dependenciesBecomeLibraryComponentsWithPurlHashAndLicense() {
        String sbom = new SbomGenerator().toJson(exampleApp(), "user-admin", "1.0.0",
                DEPENDENCIES);

        assertThat(sbom)
                .contains("\"purl\" : \"pkg:maven/org.postgresql/postgresql@42.7.4\"")
                .contains("\"purl\" : \"pkg:maven/org.apache.camel/camel-core@4.18.0\"")
                .contains("\"name\" : \"BSD-2-Clause\"")
                .contains("\"type\" : \"library\"")
                // Source file components are still present alongside the libraries.
                .contains("config/tesseraql.yml");
    }

    @Test
    void dependenciesAreOrderedByPurlForReproducibility() {
        SbomGenerator generator = new SbomGenerator();
        String forward = generator.toJson(exampleApp(), "a", "1", DEPENDENCIES);
        String reversed = generator.toJson(exampleApp(), "a", "1",
                List.of(DEPENDENCIES.get(1), DEPENDENCIES.get(0)));
        assertThat(forward).isEqualTo(reversed);
        assertThat(forward.indexOf("camel-core")).isLessThan(forward.indexOf("postgresql"));
    }
}
