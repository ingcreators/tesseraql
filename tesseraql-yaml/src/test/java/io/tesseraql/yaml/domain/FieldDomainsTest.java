package io.tesseraql.yaml.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FieldDomainsTest {

    @Test
    void loadsDomainsAndConstraintsAcrossFiles(@TempDir Path dir) throws Exception {
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
        Files.writeString(dir.resolve("domains/identity.yml"), """
                version: tesseraql/v1
                domains:
                  email:
                    type: string
                    format: email
                    maxLength: 254
                    classification: personal
                    mask: fixed
                """);

        FieldDomains domains = FieldDomains.load(dir);

        assertThat(domains.domains()).containsOnlyKeys("sku", "email");
        assertThat(domains.domains().get("sku").maxLength()).isEqualTo(40);
        assertThat(domains.domains().get("email").mask()).isEqualTo("fixed");
        assertThat(domains.constraints()).containsOnlyKeys("uq_products_sku");
        assertThat(domains.require("sku", "test").pattern()).isEqualTo("[A-Z0-9-]+");
    }

    @Test
    void anAppWithoutADomainsDirectoryIsEmpty(@TempDir Path dir) {
        assertThat(FieldDomains.load(dir).isEmpty()).isTrue();
    }

    @Test
    void operationalKeysAreRejectedInsideADomain(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/bad.yml"), """
                version: tesseraql/v1
                domains:
                  sku:
                    type: string
                    required: true
                """);

        assertThatThrownBy(() -> FieldDomains.load(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("required");
    }

    @Test
    void duplicateNamesAndUnknownReferencesFail(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/a.yml"), """
                version: tesseraql/v1
                domains:
                  sku: { type: string }
                """);
        Files.writeString(dir.resolve("domains/b.yml"), """
                version: tesseraql/v1
                domains:
                  sku: { type: string }
                """);

        assertThatThrownBy(() -> FieldDomains.load(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("declared twice");

        Files.delete(dir.resolve("domains/b.yml"));
        FieldDomains domains = FieldDomains.load(dir);
        assertThatThrownBy(() -> domains.require("skuu", "web/products/post.yml"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown domain 'skuu'");
    }

    @Test
    void aDomainsDocumentMustDeclareTheVersion(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/x.yml"), "domains:\n  sku: { type: string }\n");

        assertThatThrownBy(() -> FieldDomains.load(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("version");
    }
}
