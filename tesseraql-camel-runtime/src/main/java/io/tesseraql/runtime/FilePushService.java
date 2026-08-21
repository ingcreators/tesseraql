package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.connectors.FileConnectors;
import io.tesseraql.yaml.model.PushSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Delivers a produced file to a {@code push:} step's target (docs/analytics-experience.md):
 * a local drop directory under the push block's {@code allowedPaths} roots, or a remote
 * SFTP/FTPS server under the same deny-by-default allow-list, credential, and
 * server-verification treatment the {@code poll:} consumers get — the client is the
 * one its poll sibling reads with, so the two directions cannot drift apart.
 *
 * <p>Delivery is atomic for the partner's poller: the local target writes a {@code .part} file
 * and renames it into place, and the remote targets do the same thing over the wire — uploaded
 * under a dot-name, renamed on completion.
 *
 * <p>A push connects when it delivers and disconnects afterwards, so a job with no push step
 * costs nothing and nothing is held between deliveries (docs/camel-removal.md decision 4).
 */
public final class FilePushService implements AutoCloseable {

    /** TQL-SEC-4141: a push target's host is not in the push allow-list (deny by default). */
    private static final TqlErrorCode HOST_NOT_ALLOWED = new TqlErrorCode(TqlDomain.SEC, 4141);
    /** TQL-BATCH-5315: the delivery failed (connect, authenticate, write, or rename). */
    private static final TqlErrorCode PUSH_FAILED = new TqlErrorCode(TqlDomain.BATCH, 5315);

    private final FileConnectors connectors;
    private final Path appHome;
    public FilePushService(FileConnectors connectors, Path appHome) {
        this.connectors = connectors;
        this.appHome = appHome.toAbsolutePath().normalize();
    }

    /**
     * Delivers {@code content} as {@code filename} into the target directory. The filename is
     * the transfer's own (or the step's interpolated {@code as:}) and must be a bare name —
     * separators and traversal are refused before anything connects.
     */
    public void push(PushSpec spec, String filename, InputStream content) {
        if (filename == null || filename.isBlank() || filename.contains("/")
                || filename.contains("\\") || filename.contains("..")
                || filename.contains("${")) {
            throw new TqlException(PUSH_FAILED, "Push filename '" + filename
                    + "' must be a plain file name");
        }
        switch (spec.effectiveTransport()) {
            case "local" -> pushLocal(spec, filename, content);
            case "sftp", "ftps" -> pushRemote(spec, filename, content);
            default -> throw new TqlException(PUSH_FAILED,
                    "Unsupported push transport '" + spec.transport() + "'");
        }
    }

    /** Write beside the destination, then rename: a partner poller never sees a partial file. */
    private void pushLocal(PushSpec spec, String filename, InputStream content) {
        Path directory = connectors.requireAllowedPath(appHome, spec.path());
        try {
            Files.createDirectories(directory);
            Path part = directory.resolve(filename + ".part");
            try (content) {
                Files.copy(content, part, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(part, directory.resolve(filename),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException crossDevice) {
                Files.move(part, directory.resolve(filename),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new TqlException(PUSH_FAILED, "Push to '" + spec.path() + "' failed: "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Delivers to a remote target, through the same client its poll sibling reads with
     * (docs/camel-removal.md decision 4).
     *
     * <p>Both directions calling one implementation is the property the shared URI builder existed
     * to keep: every transport and identity guarantee — the host-key check, the encrypted FTPS data
     * channel, the exactly-one-authentication-method rule — has to hold for a push exactly as it
     * holds for a poll, and the year the data channel stayed unencrypted is what happens when they
     * are written twice.
     */
    private void pushRemote(PushSpec spec, String filename, InputStream content) {
        if (!connectors.isHostAllowed(spec.host())) {
            throw new TqlException(HOST_NOT_ALLOWED, "Push target host '" + spec.host()
                    + "' is not in tesseraql.connectors.push.allowedHosts (deny by default)");
        }
        String scheme = spec.effectiveTransport();
        try (content; RemoteFiles remote = remote(spec)) {
            remote.upload(filename, content);
        } catch (TqlException refused) {
            throw refused;
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new TqlException(PUSH_FAILED, "Push to " + scheme + "://" + spec.host()
                    + "/" + spec.path() + " failed: " + cause.getMessage(), ex);
        }
    }

    /** The client for this target, connected on first use and closed with the delivery. */
    private RemoteFiles remote(PushSpec spec) {
        return "sftp".equals(spec.effectiveTransport())
                ? new SftpClient(SftpClient.settings(connectors, spec.host(), spec.port(),
                        spec.path(), spec.credential(), appHome))
                : new FtpsClient(FtpsClient.settings(connectors, spec.host(), spec.port(),
                        spec.path(), spec.credential(), appHome));
    }

    @Override
    public void close() {
        // Nothing held between deliveries: a push connects, uploads and disconnects, so there is
        // no context, template or producer cache to stop. The method stays because the CLI and the
        // runtime both own one of these and both close what they own.
    }
}
