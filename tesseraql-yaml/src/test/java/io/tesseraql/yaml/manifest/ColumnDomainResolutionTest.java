package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.ColumnSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file column's {@code domain:} resolves in the manifest loader (docs/temporal-semantics.md
 * decision 25): the domain's {@code type} and {@code format} — the two keys a column and a
 * field share — land under the column's own, on a route's {@code import:} and {@code export:}.
 */
class ColumnDomainResolutionTest {

    private Path app(@TempDir Path dir, String importColumn) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  held_date:
                    type: date
                    format: yyyy/MM/dd
                    locale: de-DE
                    maxLength: 10
                  fee_amount:
                    type: number
                    format: "#,##0.00"
                """);
        Path importRoute = dir.resolve("web/api/events/import");
        Files.createDirectories(importRoute);
        Files.writeString(importRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: events.import
                kind: route
                recipe: file-import
                security:
                  auth: public
                import:
                  format: csv
                  columns:
                    - name
                    - %s
                    - { name: fee, domain: fee_amount, format: "#,##0" }
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(importColumn));
        Files.writeString(importRoute.resolve("upsert.sql"), "select 1\n");
        Path exportRoute = dir.resolve("web/api/events/export");
        Files.createDirectories(exportRoute);
        Files.writeString(exportRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: events.export
                kind: route
                recipe: file-export
                security:
                  auth: public
                export:
                  format: csv
                  filename: events.csv
                  columns:
                    - name
                    - { name: held_on, label: Held, domain: held_date }
                sources:
                  main:
                    sql:
                      file: select.sql
                """);
        Files.writeString(exportRoute.resolve("select.sql"), "select 1\n");
        return dir;
    }

    @Test
    void theDomainsTypeAndFormatMergeUnderAnImportAndAnExportColumn(@TempDir Path dir)
            throws Exception {
        AppManifest manifest = new ManifestLoader().load(
                app(dir, "{ name: held_on, domain: held_date }"));

        RouteDefinition importing = route(manifest, "events.import");
        ColumnSpec heldOn = importing.fileImport().columns().get(1);
        assertThat(heldOn.name()).isEqualTo("held_on");
        assertThat(heldOn.type()).isEqualTo("date");
        // A value only the domain has: the compiled column carries it.
        assertThat(heldOn.format()).isEqualTo("yyyy/MM/dd");
        assertThat(heldOn.domain()).isEqualTo("held_date");
        // The column's own format wins over the domain's.
        ColumnSpec fee = importing.fileImport().columns().get(2);
        assertThat(fee.type()).isEqualTo("number");
        assertThat(fee.format()).isEqualTo("#,##0");
        // The bare name is untouched.
        assertThat(importing.fileImport().columns().get(0).type()).isNull();

        ColumnSpec exported = route(manifest, "events.export").fileExport().columns().get(1);
        assertThat(exported.type()).isEqualTo("date");
        assertThat(exported.format()).isEqualTo("yyyy/MM/dd");
        // The file-side keys are the column's own.
        assertThat(exported.label()).isEqualTo("Held");
    }

    @Test
    void anUnknownDomainOnAColumnFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "{ name: held_on, domain: held_dat }");

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown domain 'held_dat'");
    }

    private static RouteDefinition route(AppManifest manifest, String id) {
        return manifest.routes().stream().filter(r -> r.definition().id().equals(id))
                .findFirst().orElseThrow().definition();
    }
}
