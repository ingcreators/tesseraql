package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.PollSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 26 {@code poll:} trigger on a {@code file-import} job: a local directory and a
 * remote SFTP source, each carrying the {@code import:} block the polled files flow through.
 */
class PollTriggerParsingTest {

    @Test
    void parsesALocalDirectoryPollImportJob(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path jobDir = dir.resolve("batch/intake");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    source: local
                    path: /data/inbound/orders
                    include: "*.csv"
                    delay: 30s
                import:
                  format: csv
                  columns: [orderNo, qty]
                  onError: skip
                  sql:
                    file: upsert-order.sql
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        JobDefinition job = manifest.jobs().get(0).definition();

        assertThat(job.recipe()).isEqualTo("file-import");
        PollSpec poll = job.trigger().poll();
        assertThat(poll).isNotNull();
        assertThat(poll.effectiveSource()).isEqualTo("local");
        assertThat(poll.isRemote()).isFalse();
        assertThat(poll.path()).isEqualTo("/data/inbound/orders");
        assertThat(poll.include()).isEqualTo("*.csv");
        assertThat(poll.effectiveDelay()).isEqualTo("30s");
        assertThat(poll.effectiveMove()).isEqualTo(".done");
        assertThat(poll.effectiveMoveFailed()).isEqualTo(".error");
        assertThat(job.fileImport()).isNotNull();
        assertThat(job.fileImport().format()).isEqualTo("csv");
        assertThat(job.fileImport().effectiveOnError()).isEqualTo("skip");
        assertThat(job.fileImport().sql().file()).isEqualTo("upsert-order.sql");
    }

    @Test
    void parsesARemoteSftpPollImportJob(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path jobDir = dir.resolve("batch/partner");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: partner.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    source: sftp
                    host: sftp.partner.example
                    port: 2222
                    path: /outbound/orders
                    credential: partner-sftp
                    move: archive
                    moveFailed: rejected
                import:
                  format: csv
                  sql:
                    file: upsert-order.sql
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        PollSpec poll = manifest.jobs().get(0).definition().trigger().poll();

        assertThat(poll.effectiveSource()).isEqualTo("sftp");
        assertThat(poll.isRemote()).isTrue();
        assertThat(poll.host()).isEqualTo("sftp.partner.example");
        assertThat(poll.port()).isEqualTo(2222);
        assertThat(poll.path()).isEqualTo("/outbound/orders");
        assertThat(poll.credential()).isEqualTo("partner-sftp");
        assertThat(poll.effectiveMove()).isEqualTo("archive");
        assertThat(poll.effectiveMoveFailed()).isEqualTo("rejected");
        // The default poll interval applies when none is declared.
        assertThat(poll.effectiveDelay()).isEqualTo("60s");
    }
}
