package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tenancy block's vocabulary lint (TQL-TENANT-3002, docs/audit-low-leads.md G25): a
 * misspelled mode or resolver type is a build error, as the runtime refuses it at boot.
 */
class AppLinterTenancyConfigTest {

    @Test
    void aMisspelledModeIsAnError(@TempDir Path dir) throws Exception {
        writeConfig(dir, """
                tenancy:
                  enabled: true
                  mode: schema_per_tenant
                  datasources:
                    acme: { jdbcUrl: jdbc:h2:mem:acme }
                """);
        assertThat(messages(dir)).singleElement().asString()
                .contains("TQL-TENANT-3002").contains("schema_per_tenant");
    }

    @Test
    void aMisspelledResolverTypeIsAnError(@TempDir Path dir) throws Exception {
        writeConfig(dir, """
                tenancy:
                  enabled: true
                  mode: shared-schema
                  resolver:
                    type: claims
                    source: tenantId
                """);
        assertThat(messages(dir)).singleElement().asString()
                .contains("TQL-TENANT-3002").contains("claims");
    }

    @Test
    void aPerTenantModeWithoutPoolsIsAnError(@TempDir Path dir) throws Exception {
        writeConfig(dir, """
                tenancy:
                  enabled: true
                  mode: database-per-tenant
                """);
        assertThat(messages(dir)).singleElement().asString()
                .contains("TQL-TENANT-3002").contains("tenancy.datasources");
    }

    @Test
    void theVocabularyLintsCleanAndADisabledTenancyIsNotRead(@TempDir Path dir)
            throws Exception {
        writeConfig(dir, """
                tenancy:
                  enabled: true
                  mode: schema-per-tenant
                  resolver:
                    type: host
                    source: "{tenant}.example.com"
                  datasources:
                    acme: { jdbcUrl: jdbc:h2:mem:acme }
                """);
        assertThat(messages(dir)).isEmpty();
        writeConfig(dir, """
                tenancy:
                  enabled: false
                  mode: schema_per_tenant
                """);
        assertThat(messages(dir)).isEmpty();
    }

    private static List<String> messages(Path dir) {
        return new AppLinter().lint(dir).stream()
                .filter(f -> f.code().equals("TQL-TENANT-3002"))
                .map(f -> f.code() + " " + f.message()).toList();
    }

    private static void writeConfig(Path dir, String tenancy) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), tenancy);
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
    }
}
