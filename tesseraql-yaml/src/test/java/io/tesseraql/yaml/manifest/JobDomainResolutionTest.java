package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.JobDefinition;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A job resolves its domain references exactly as a route does, in the manifest loader
 * (docs/temporal-semantics.md decision 26, audit lead 27): its {@code input:} fields and their
 * object-array elements, each export step's columns, a poll job's import columns. Before, a
 * job's {@code domain:} was parsed and never merged, so {@code count: { domain: batch_count }}
 * bound as an untyped string with the domain's keys applied nowhere.
 */
class JobDomainResolutionTest {

    private Path app(@TempDir Path dir, String countDomain) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/batch.yml"), """
                version: tesseraql/v1
                domains:
                  batch_count:
                    type: integer
                    min: 1
                    max: 500
                  line_sku:
                    type: string
                    pattern: "[A-Z0-9-]+"
                  held_date:
                    type: date
                    format: yyyy/MM/dd
                """);
        Files.createDirectories(dir.resolve("batch/nightly"));
        Files.writeString(dir.resolve("batch/nightly/job.yml"), """
                version: tesseraql/v1
                id: nightly
                kind: job
                recipe: batch-pipeline
                input:
                  count: { domain: %s, required: true }
                  note: { type: string, maxLength: 20 }
                  lines:
                    type: array
                    items:
                      fields:
                        sku: { domain: line_sku }
                pipeline:
                  - id: dump
                    sql:
                      file: select.sql
                      mode: query
                    export:
                      format: csv
                      filename: nightly.csv
                      columns:
                        - name
                        - { name: held_on, domain: held_date }
                """.formatted(countDomain));
        Files.writeString(dir.resolve("batch/nightly/select.sql"),
                "select name, held_on from events\n");
        Files.createDirectories(dir.resolve("batch/inbound"));
        Files.writeString(dir.resolve("batch/inbound/job.yml"), """
                version: tesseraql/v1
                id: inbound
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: inbox
                import:
                  format: csv
                  columns:
                    - name
                    - { name: held_on, domain: held_date }
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);
        Files.writeString(dir.resolve("batch/inbound/upsert.sql"), "select 1\n");
        return dir;
    }

    @Test
    void aJobsInputAndColumnsCarryTheirDomainsKeys(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir, "batch_count"));

        JobDefinition nightly = job(manifest, "nightly");
        InputField count = nightly.input().get("count");
        // Values only the domain has: the compiled job carries them; the job keeps its own
        // operational choice.
        assertThat(count.type()).isEqualTo("integer");
        assertThat(count.max()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(count.required()).isTrue();
        assertThat(count.domain()).isEqualTo("batch_count");
        assertThat(nightly.input().get("note").maxLength()).isEqualTo(20);
        // An object array's element field, the same.
        assertThat(nightly.input().get("lines").items().fields().get("sku").pattern())
                .isEqualTo("[A-Z0-9-]+");
        // An export step's column.
        assertThat(nightly.pipeline().get(0).export().columns().get(1).format())
                .isEqualTo("yyyy/MM/dd");
        // A poll job's import column.
        assertThat(job(manifest, "inbound").fileImport().columns().get(1).type())
                .isEqualTo("date");
    }

    @Test
    void anUnknownDomainOnAJobInputFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "batch_coun");

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown domain 'batch_coun'");
    }

    private static JobDefinition job(AppManifest manifest, String id) {
        return manifest.jobs().stream().filter(j -> j.definition().id().equals(id))
                .findFirst().orElseThrow().definition();
    }
}
