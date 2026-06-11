package io.tesseraql.core.spool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTempStoreTest {

    @Test
    void writesReadsAndDeletes(@TempDir Path dir) throws IOException {
        FileTempStore store = new FileTempStore(dir.resolve("spool"));

        SpoolRef ref;
        try (SpoolWriter writer = store.createWriter(SpoolKind.CSV)) {
            writer.write("id,name\n".getBytes(StandardCharsets.UTF_8));
            writer.write("1,sato\n".getBytes(StandardCharsets.UTF_8));
            writer.incrementRows(1);
            writer.close();
            ref = writer.toRef();
        }

        assertThat(ref.kind()).isEqualTo(SpoolKind.CSV);
        assertThat(ref.rows()).isEqualTo(1);
        assertThat(ref.bytes()).isGreaterThan(0);

        try (InputStream in = store.openInput(ref)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("id,name\n1,sato\n");
        }

        store.delete(ref);
        assertThat(java.nio.file.Files.exists(java.nio.file.Paths.get(ref.uri()))).isFalse();
    }
}
