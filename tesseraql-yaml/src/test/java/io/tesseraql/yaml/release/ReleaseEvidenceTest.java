package io.tesseraql.yaml.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.sbom.SbomGenerator;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseEvidenceTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void evidenceContainsManifestAndGeneratedHashes() {
        AppManifest manifest = exampleApp();
        ReleaseEvidence evidence = new ReleaseEvidence();
        Map<String, Object> doc = evidence.build(manifest, "com.example.user-admin", "1.0.0");

        assertThat(doc.get("manifestSha256")).isEqualTo(manifest.index().aggregateHash());
        assertThat(doc).containsKey("files");
        assertThat(((Map<?, ?>) doc.get("generated")).get("openapiSha256")).asString().hasSize(64);
    }

    @Test
    void evidenceIsDeterministic() {
        AppManifest manifest = exampleApp();
        ReleaseEvidence evidence = new ReleaseEvidence();
        assertThat(evidence.toJson(manifest, "a", "1"))
                .isEqualTo(evidence.toJson(manifest, "a", "1"));
    }

    @Test
    void sbomListsSourceFilesWithHashes() {
        String sbom = new SbomGenerator().toJson(exampleApp(), "user-admin", "1.0.0");
        assertThat(sbom).contains("\"bomFormat\" : \"CycloneDX\"");
        assertThat(sbom).contains("config/tesseraql.yml").contains("\"alg\" : \"SHA-256\"");
    }
}
