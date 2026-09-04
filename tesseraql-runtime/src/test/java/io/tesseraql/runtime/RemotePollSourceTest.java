package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The remote poll source's download target (docs/poll-connector-hardening.md).
 *
 * <p>A listed name is the one input to a poll cycle that the <em>server</em> chooses, and the SFTP
 * client verifies server identity only when a {@code knownHostsFile} is configured — so an
 * impersonated partner is inside the accepted threat model, and a listing entry naming a path
 * outside the work directory was an arbitrary file write. The poll loop refuses such a name before
 * it ever gets here; this is the same rule at the mechanism, so the class holds whichever caller
 * drives it.
 */
class RemotePollSourceTest {

    @TempDir
    Path work;

    @Test
    void aNameThatEscapesTheWorkDirectoryIsRefused() {
        RemotePollSource source = new RemotePollSource(new WritingFiles(), work.resolve("job"));

        assertThatThrownBy(() -> source.fetch(new PollSource.PolledFile("../escape.csv", 3, 1L)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the work directory");
        assertThat(work.resolve("escape.csv")).doesNotExist();
    }

    @Test
    void anAbsoluteNameIsRefused() {
        RemotePollSource source = new RemotePollSource(new WritingFiles(), work.resolve("job"));

        assertThatThrownBy(() -> source.fetch(
                new PollSource.PolledFile("/etc/tesseraql-owned", 3, 1L)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the work directory");
    }

    @Test
    void aPlainNameDownloadsIntoTheWorkDirectory() throws IOException {
        Path jobWork = work.resolve("job");
        RemotePollSource source = new RemotePollSource(new WritingFiles(), jobWork);

        PollSource.Fetched fetched = source.fetch(new PollSource.PolledFile("orders.csv", 3, 1L));

        assertThat(fetched.path()).isEqualTo(jobWork.resolve("orders.csv"));
        assertThat(Files.readString(fetched.path())).isEqualTo("ok\n");
    }

    /** A transport that actually writes, so a target outside the directory would leave a file. */
    private static final class WritingFiles implements RemoteFiles {

        @Override
        public List<PollSource.PolledFile> list() {
            return List.of();
        }

        @Override
        public Optional<PollSource.PolledFile> stat(String name) {
            return Optional.empty();
        }

        @Override
        public void download(String name, Path target) throws IOException {
            Files.createDirectories(target.getParent());
            Files.writeString(target, "ok\n");
        }

        @Override
        public void upload(String filename, InputStream content) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public void archive(String name, String subDirectory) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }
}
