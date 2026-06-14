package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.HttpCallSpec;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.PipelineStep;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 26 {@code http-call} pipeline step: an outbound REST call interleaved with
 * SQL steps, whose response a later step binds.
 */
class HttpCallStepParsingTest {

    @Test
    void parsesAnHttpCallPipelineStep(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path jobDir = dir.resolve("batch/sync");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: orders.sync
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: pending
                    sql:
                      file: select-pending.sql
                      mode: query-spool
                  - id: push
                    http-call:
                      method: POST
                      url: https://api.partner.example/v1/orders
                      credential: partner
                      headers:
                        X-Source: tesseraql
                      query:
                        batch: job.businessDate
                      body: step.pending.spool
                      expectStatus: 201
                      requestTimeout: 20s
                  - id: mark
                    sql:
                      file: mark-pushed.sql
                      mode: update
                      params:
                        status: step.push.status
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        JobDefinition job = manifest.jobs().get(0).definition();

        assertThat(job.pipeline()).hasSize(3);
        PipelineStep push = job.pipeline().get(1);
        assertThat(push.sql()).isNull();
        assertThat(push.notification()).isNull();

        HttpCallSpec call = push.httpCall();
        assertThat(call).isNotNull();
        assertThat(call.effectiveMethod()).isEqualTo("POST");
        assertThat(call.url()).isEqualTo("https://api.partner.example/v1/orders");
        assertThat(call.credential()).isEqualTo("partner");
        assertThat(call.headers()).containsEntry("X-Source", "tesseraql");
        assertThat(call.query()).containsEntry("batch", "job.businessDate");
        assertThat(call.body()).isEqualTo("step.pending.spool");
        assertThat(call.expectStatus()).isEqualTo(201);
        assertThat(call.requestTimeout()).isEqualTo("20s");
    }

    @Test
    void methodDefaultsToGet(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path jobDir = dir.resolve("batch/fetch");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: rates.fetch
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: fetch
                    http-call:
                      url: https://api.partner.example/v1/rates
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        HttpCallSpec call = manifest.jobs().get(0).definition().pipeline().get(0).httpCall();
        assertThat(call.effectiveMethod()).isEqualTo("GET");
        assertThat(call.headers()).isEmpty();
        assertThat(call.query()).isEmpty();
    }
}
