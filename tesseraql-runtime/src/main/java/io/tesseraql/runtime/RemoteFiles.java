package io.tesseraql.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One remote directory, in the five operations both directions need
 * (docs/camel-removal.md decision 4).
 *
 * <p>The {@code poll:} consumers and the {@code push:} producers were assembled from the same
 * endpoint-URI builder for a reason that still holds: **every transport and identity guarantee has
 * to be true in both directions**, and the FTPS data channel stayed unencrypted for a year when
 * that logic had a consumer home and a lookalike copy. So the connection, the credential rules and
 * the transport settings live in one implementation per protocol, and both directions call it.
 *
 * <p>{@link RemotePollSource} adapts any of these into a {@link PollSource}; {@code FilePushService}
 * calls {@link #upload} on the same object.
 */
interface RemoteFiles extends AutoCloseable {

    /** A remote poll source / push target declared without a credential (docs/connectors.md). */
    io.tesseraql.core.error.TqlErrorCode REMOTE_NEEDS_CREDENTIAL = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.SEC, 4088);

    /** A connector credential declaring no authentication method, or more than one. */
    io.tesseraql.core.error.TqlErrorCode CREDENTIAL_METHOD = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.SEC, 4089);

    /** Every plain file directly in the configured directory. */
    List<PollSource.PolledFile> list() throws IOException;

    /** One file's fingerprint as it stands now, or empty when it has gone. */
    Optional<PollSource.PolledFile> stat(String name) throws IOException;

    /** Copies a remote file to {@code target} on local disk. */
    void download(String name, Path target) throws IOException;

    /**
     * Writes {@code content} as {@code filename}, atomically as far as a reader is concerned.
     *
     * <p>Both implementations upload under a dot-name and rename on completion: a partner's poller
     * must never read a file this one is still writing.
     */
    void upload(String filename, InputStream content) throws IOException;

    /** Moves a file into a sub-directory of the configured directory, creating it when absent. */
    void archive(String name, String subDirectory) throws IOException;

    @Override
    void close();
}
