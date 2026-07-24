package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest loader resolves {@code domain:} references and merges the app constraint catalog
 * (docs/field-domains.md), so every consumer sees fully-populated inputs.
 */
class FieldDomainResolutionTest {

    private Path app(@TempDir Path dir) throws Exception {
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
                constraints:
                  uq_products_sku:
                    field: sku
                    code: duplicate
                """);
        Files.createDirectories(dir.resolve("web/products/adjust"));
        Files.writeString(dir.resolve("web/products/adjust/post.yml"), """
                version: tesseraql/v1
                id: products.adjust
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                  policy: inv.write
                input:
                  sku:
                    domain: sku
                    required: true
                  note:
                    type: string
                    maxLength: 20
                steps:
                  adjust:
                    file: adjust.sql
                response:
                  json:
                    body:
                      ok: "true"
                """);
        Files.writeString(dir.resolve("web/products/adjust/adjust.sql"), "select 1\n");
        return dir;
    }

    @Test
    void domainKeysMergeUnderTheRouteDeclaration(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir));

        RouteDefinition def = manifest.routes().get(0).definition();
        InputField sku = def.input().get("sku");
        // The domain fills the field itself; the route keeps its operational choice.
        assertThat(sku.type()).isEqualTo("string");
        assertThat(sku.maxLength()).isEqualTo(40);
        assertThat(sku.pattern()).isEqualTo("[A-Z0-9-]+");
        assertThat(sku.required()).isTrue();
        assertThat(sku.domain()).isEqualTo("sku");
        // A route-local field without a reference is untouched.
        assertThat(def.input().get("note").maxLength()).isEqualTo(20);
    }

    @Test
    void theConstraintCatalogIsInheritedAndRouteEntriesWin(@TempDir Path dir) throws Exception {
        Path home = app(dir);

        AppManifest manifest = new ManifestLoader().load(home);
        RouteDefinition def = manifest.routes().get(0).definition();
        assertThat(def.errors().constraints().get("uq_products_sku").field()).isEqualTo("sku");

        // A route-local mapping for the same constraint name overrides the catalog's.
        Files.writeString(home.resolve("web/products/adjust/post.yml"),
                Files.readString(home.resolve("web/products/adjust/post.yml")) + """
                        errors:
                          constraints:
                            uq_products_sku:
                              field: sku
                              code: taken
                        """);
        RouteDefinition overridden = new ManifestLoader().load(home).routes().get(0).definition();
        assertThat(overridden.errors().constraints().get("uq_products_sku").code())
                .isEqualTo("taken");
    }

    @Test
    void anUnknownDomainReferenceFailsTheLoad(@TempDir Path dir) throws Exception {
        Path home = app(dir);
        Files.writeString(home.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  productCode: { type: string }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(home))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown domain 'sku'");
    }
}
