package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints around field domains (docs/field-domains.md). */
class AppLinterFieldDomainsTest {

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
                  legacyCode:
                    type: string
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                input:
                  sku:
                %s
                sql:
                  file: search.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """.formatted(skuInput));
        Files.writeString(dir.resolve("web/api/items/search.sql"), "select 1\n");
        return dir;
    }

    @Test
    void aLooseningOverrideIsFlagged(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    domain: sku\n    maxLength: 60"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-4610") && !f.isError()
                && f.message().contains("maxLength 60 > 40"));
    }

    @Test
    void anUnreferencedDomainIsFlagged(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "    domain: sku"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-4611")
                && f.message().contains("legacyCode"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4610"));
    }

    @Test
    void aTighteningOverrideLintsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "    domain: sku\n    maxLength: 20"));

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4610"));
    }
}
