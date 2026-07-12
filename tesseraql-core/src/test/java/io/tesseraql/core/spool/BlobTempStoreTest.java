package io.tesseraql.core.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.blob.FileBlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The blob-backed temp store (docs/deployment.md, "Shared export files"): spools round-trip
 * through the {@link io.tesseraql.core.blob.BlobStore} SPI, the spool id doubling as the blob
 * key so a ref needs no side table.
 */
class BlobTempStoreTest {

    @Test
    void spoolsRoundTripThroughTheBlobStore(@TempDir Path dir) throws Exception {
        BlobTempStore store = new BlobTempStore(new FileBlobStore(dir), "temp");

        byte[] payload = "id,status\n1,PENDING\n".getBytes(StandardCharsets.UTF_8);
        SpoolWriter writer = store.createWriter(SpoolKind.CSV);
        writer.write(payload);
        writer.incrementRows(1);
        writer.close();
        SpoolRef ref = writer.toRef();
        assertThat(ref.bytes()).isEqualTo(payload.length);
        assertThat(ref.rows()).isEqualTo(1);
        assertThat(ref.uri().toString()).startsWith("tql-temp-blob:");

        try (InputStream in = store.openInput(ref)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }

        store.delete(ref);
        assertThatThrownBy(() -> store.openInput(ref)).isInstanceOf(IOException.class);
    }
}
