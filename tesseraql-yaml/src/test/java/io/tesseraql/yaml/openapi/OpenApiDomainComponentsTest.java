package io.tesseraql.yaml.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Field domains become named component schemas (docs/field-domains.md). */
class OpenApiDomainComponentsTest {

    private Path app(@TempDir Path dir, String skuInput) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  sku:
                    type: string
                    maxLength: 40
                    pattern: "[A-Z0-9-]+"
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                input:
                  sku:
                %s
                steps:
                  record:
                    file: insert.sql
                response:
                  json:
                    body:
                      ok: "true"
                """.formatted(skuInput));
        Files.writeString(dir.resolve("web/api/items/insert.sql"), "select 1\n");
        return dir;
    }

    @SuppressWarnings("unchecked")
    @Test
    void aPureDomainReferenceEmitsANamedComponent(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir,
                "    domain: sku\n    required: true"));

        Map<String, Object> doc = new OpenApiGenerator().generate(manifest);

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc
                .get("components")).get("schemas");
        assertThat(schemas).containsKey("domain.sku");
        assertThat((Map<String, Object>) schemas.get("domain.sku"))
                .containsEntry("maxLength", 40)
                .containsEntry("pattern", "[A-Z0-9-]+");
        assertThat(new OpenApiGenerator().toJson(manifest))
                .contains("#/components/schemas/domain.sku");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aTighteningOverrideStaysInlineSoTheContractShowsIt(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir,
                "    domain: sku\n    required: true\n    maxLength: 20"));

        Map<String, Object> doc = new OpenApiGenerator().generate(manifest);

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc
                .get("components")).get("schemas");
        assertThat(schemas).doesNotContainKey("domain.sku");
        assertThat(new OpenApiGenerator().toJson(manifest)).contains("\"maxLength\" : 20");
    }
}
