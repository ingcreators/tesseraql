package io.tesseraql.yaml.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HtmxContractGeneratorTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPagesAndFragmentsWithTemplatesInputsAndSecurity() {
        Map<String, Object> doc = new HtmxContractGenerator().generate(exampleApp());

        assertThat(doc.get("contract")).isEqualTo("tesseraql-htmx/v1");
        List<Map<String, Object>> pages = (List<Map<String, Object>>) doc.get("pages");
        List<Map<String, Object>> fragments = (List<Map<String, Object>>) doc.get("fragments");

        assertThat(pages).anySatisfy(page -> assertThat(page.get("path")).isEqualTo("/users"));
        assertThat(fragments).anySatisfy(fragment -> {
            assertThat(fragment.get("path")).isEqualTo("/users/fragments/table");
            assertThat(fragment.get("method")).isEqualTo("GET");
            assertThat(fragment.get("template")).isEqualTo("table.html");
            Map<String, Object> inputs = (Map<String, Object>) fragment.get("inputs");
            assertThat(inputs).containsKey("q");
            Map<String, Object> security = (Map<String, Object>) fragment.get("security");
            assertThat(security).containsEntry("auth", "browser").containsEntry("policy",
                    "users.read");
        });

        // JSON API routes are not part of the hypermedia contract.
        assertThat(pages).noneSatisfy(
                page -> assertThat(String.valueOf(page.get("path"))).startsWith("/api/"));
        assertThat(fragments).noneSatisfy(
                fragment -> assertThat(String.valueOf(fragment.get("path"))).startsWith("/api/"));
    }

    @Test
    void outputIsDeterministic() {
        AppManifest manifest = exampleApp();
        HtmxContractGenerator generator = new HtmxContractGenerator();
        assertThat(generator.toJson(manifest)).isEqualTo(generator.toJson(manifest));
    }
}
