package io.tesseraql.yaml.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.model.InputField;
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

    /**
     * {@code lookup:} is a describing key (docs/reference-lookup.md decision 1): a domain may
     * declare the master reference once, and a referencing field inherits it whole.
     */
    @Test
    void aDomainMayDeclareALookupAndAReferenceInheritsIt(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/masters.yml"), """
                version: tesseraql/v1
                domains:
                  customer:
                    type: string
                    lookup:
                      source: /api/customers/search
                      code: customer_code
                      label: name
                """);

        FieldDomains domains = FieldDomains.load(dir);
        InputField declared = new InputField(null, true, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "customer", null, null, null,
                null);
        InputField merged = declared.mergedWith(domains.require("customer", "test"));

        assertThat(merged.lookup()).isNotNull();
        assertThat(merged.lookup().source()).isEqualTo("/api/customers/search");
        assertThat(merged.lookup().code()).isEqualTo("customer_code");
        assertThat(merged.lookup().label()).isEqualTo("name");
        assertThat(merged.required()).isTrue();
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
    void unknownKeysAreRejectedInsideAConstraint(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("domains"));
        // `feild:` is a typo for field:; ignoreUnknown would drop the field mapping so the DB
        // violation surfaces as an opaque error instead of a field error.
        Files.writeString(dir.resolve("domains/bad.yml"), """
                version: tesseraql/v1
                constraints:
                  uq_users_email:
                    feild: email
                    code: duplicate
                """);

        assertThatThrownBy(() -> FieldDomains.load(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("feild");
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

    @Test
    void domainReferencesResolveInConsumersAndToolsToo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  sku:
                    type: string
                    maxLength: 40
                    pattern: "[A-Z0-9-]+"
                """);
        Files.createDirectories(dir.resolve("consume/products"));
        Files.writeString(dir.resolve("consume/products/adjusted.yml"), """
                version: tesseraql/v1
                id: products.adjusted
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: products.adjusted
                input:
                  sku: { domain: sku, required: true }
                steps:
                  - id: main
                    sql:
                      file: apply.sql
                """);
        Files.writeString(dir.resolve("consume/products/apply.sql"), "select 1\n");
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/lookup.yml"), """
                version: tesseraql/v1
                id: products.lookup
                kind: tool
                recipe: query-json
                description: look a product up
                security:
                  policy: inv.read
                input:
                  sku: { domain: sku, required: true }
                sources:
                  main:
                    sql:
                      file: lookup.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(dir.resolve("mcp/lookup.sql"), "select 1\n");

        AppManifest manifest = new ManifestLoader().load(dir);

        // This half failed silently: nothing resolved the reference, so the binder saw a field
        // with no type and no constraints and enforced none of them — on an MCP tool, that is an
        // agent-facing write surface advertised without the limits its author declared.
        InputField consumerField = manifest.consumers().get(0).definition().input().get("sku");
        assertThat(consumerField.type()).isEqualTo("string");
        assertThat(consumerField.maxLength()).isEqualTo(40);
        assertThat(consumerField.pattern()).isEqualTo("[A-Z0-9-]+");
        assertThat(consumerField.required()).isTrue();

        InputField toolField = manifest.tools().get(0).definition().input().get("sku");
        assertThat(toolField.type()).isEqualTo("string");
        assertThat(toolField.maxLength()).isEqualTo(40);
        assertThat(toolField.pattern()).isEqualTo("[A-Z0-9-]+");
    }
}
