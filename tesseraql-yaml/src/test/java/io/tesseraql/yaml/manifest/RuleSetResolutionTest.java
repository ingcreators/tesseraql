package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.ValidationRule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest loader resolves shared validation-rule references
 * (docs/validation-rule-sets.md): the set carries the rule, the reference carries the wiring.
 */
class RuleSetResolutionTest {

    private Path app(@TempDir Path dir, String validate) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("rules/catalog.yml"), """
                version: tesseraql/v1

                rules:
                  skuIsFree:
                    file: sku-free.sql
                    binds: [sku]
                    code: duplicate
                    message: catalog.sku.duplicate
                  quantityStaysSane:
                    rule: "params.delta >= -10000 && params.delta <= 10000"
                    code: out-of-range
                """);
        Files.writeString(dir.resolve("rules/sku-free.sql"),
                "select 'sku' as field from products where sku = /* sku */'AA-1'\n");
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
                  sku: { type: string, required: true }
                  delta: { type: integer, required: true }
                validate:
                %s
                steps:
                  adjust:
                    file: adjust.sql
                response:
                  json:
                    body:
                      ok: "true"
                """.formatted(validate));
        Files.writeString(dir.resolve("web/products/adjust/adjust.sql"), "select 1\n");
        return dir;
    }

    @Test
    void aReferenceMergesTheSharedRuleUnderTheLocalWiring(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir,
                "  skuKnown:\n"
                        + "    use: skuIsFree\n"
                        + "    params: { sku: params.sku }\n"
                        + "    field: sku\n"
                        + "  deltaSane:\n"
                        + "    use: quantityStaysSane\n"
                        + "    field: delta\n"
                        + "    code: delta-out-of-range"));

        var validate = manifest.routes().get(0).definition().validate();
        ValidationRule sql = validate.get("skuKnown");
        // The shared SQL resolves relative to the referencing route's directory.
        assertThat(sql.isSql()).isTrue();
        assertThat(sql.file()).isEqualTo("../../../rules/sku-free.sql");
        assertThat(sql.code()).isEqualTo("duplicate");
        assertThat(sql.message()).isEqualTo("catalog.sku.duplicate");
        assertThat(sql.field()).isEqualTo("sku");

        ValidationRule expr = validate.get("deltaSane");
        assertThat(expr.isExpression()).isTrue();
        // Local overrides win over the set's defaults.
        assertThat(expr.code()).isEqualTo("delta-out-of-range");
    }

    @Test
    void theBindContractIsCheckedExactly(@TempDir Path dir) throws Exception {
        Path home = app(dir, "  skuKnown:\n    use: skuIsFree\n    field: sku");

        assertThatThrownBy(() -> new ManifestLoader().load(home))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("must wire exactly the binds [sku]");
    }

    @Test
    void unknownReferencesAndInlineConflictsFail(@TempDir Path dir) throws Exception {
        Path home = app(dir, "  skuKnown:\n    use: nope\n    field: sku");
        assertThatThrownBy(() -> new ManifestLoader().load(home))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown rule 'nope'");

        Path conflicted = app(Files.createTempDirectory(dir, "b"),
                "  skuKnown:\n    use: skuIsFree\n    rule: \"1 == 1\"\n    field: sku");
        assertThatThrownBy(() -> new ManifestLoader().load(conflicted))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("use: together with rule:/file:");
    }
}
