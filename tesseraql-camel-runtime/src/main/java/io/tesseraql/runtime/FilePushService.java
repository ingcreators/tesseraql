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
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;

/**
 * Delivers a produced file to a {@code push:} step's target (docs/analytics-experience.md):
 * a local drop directory under the push block's {@code allowedPaths} roots, or a remote
 * SFTP/FTPS server under the same deny-by-default allow-list, credential, and
 * server-verification treatment the {@code poll:} consumers get — the URI mechanics are the
 * shared {@link RemoteFileUris}, so the two directions cannot drift apart.
 *
 * <p>Delivery is atomic for the partner's poller: the local target writes a {@code .part}
 * file and renames it into place; the remote targets ride the Camel producer's
 * {@code tempPrefix}, which uploads under a dot-name and renames on completion.
 *
 * <p>The Camel context is either the serving runtime's or — for a CLI {@code job run} — an
 * owned one, created lazily on the first remote push and closed with the service, so a run
 * without a push step never pays for it.
 */
public final class FilePushService implements AutoCloseable {

    /** TQL-SEC-4141: a push target's host is not in the push allow-list (deny by default). */
    private static final TqlErrorCode HOST_NOT_ALLOWED = new TqlErrorCode(TqlDomain.SEC, 4141);
    /** TQL-BATCH-5315: the delivery failed (connect, authenticate, write, or rename). */
    private static final TqlErrorCode PUSH_FAILED = new TqlErrorCode(TqlDomain.BATCH, 5315);

    private final FileConnectors connectors;
    private final Path appHome;
    private CamelContext camel;
    private final boolean ownsCamel;
    private ProducerTemplate producer;

    /** The serving runtime's shape: remote pushes ride the running context's producer. */
    public FilePushService(CamelContext camel, FileConnectors connectors, Path appHome) {
        this.camel = camel;
        this.ownsCamel = false;
        this.connectors = connectors;
        this.appHome = appHome.toAbsolutePath().normalize();
    }

    /** The CLI shape: a context of its own, started only if a remote push actually runs. */
    public FilePushService(FileConnectors connectors, Path appHome) {
        this.camel = null;
        this.ownsCamel = true;
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
            case "sftp" -> pushRemote("sftp", spec, 22, filename, content,
                    RemoteFileUris.sftpHostKeyOptions(connectors, appHome));
            case "ftps" -> pushRemote("ftps", spec, 21, filename, content,
                    RemoteFileUris.ftpsTransportOptions(connectors, appHome, spec.host()));
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

    private void pushRemote(String scheme, PushSpec spec, int defaultPort, String filename,
            InputStream content, String transportOptions) {
        if (!connectors.isHostAllowed(spec.host())) {
            throw new TqlException(HOST_NOT_ALLOWED, "Push target host '" + spec.host()
                    + "' is not in tesseraql.connectors.push.allowedHosts (deny by default)");
        }
        // tempPrefix uploads as .uploading-<name> and renames on completion — the remote
        // twin of the local .part dance, so the partner's poller never reads a partial file.
        String uri = RemoteFileUris.remoteUri(scheme, connectors, spec.host(), spec.port(),
                defaultPort, spec.path(), spec.credential(),
                "tempPrefix=.uploading-" + transportOptions);
        try (content) {
            producer().sendBodyAndHeader(uri, content, Exchange.FILE_NAME, filename);
        } catch (TqlException refused) {
            throw refused;
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new TqlException(PUSH_FAILED, "Push to " + scheme + "://" + spec.host()
                    + "/" + spec.path() + " failed: " + cause.getMessage(), ex);
        }
    }

    private synchronized ProducerTemplate producer() {
        if (camel == null) {
            DefaultCamelContext owned = new DefaultCamelContext();
            owned.start();
            camel = owned;
        }
        if (producer == null) {
            producer = camel.createProducerTemplate();
        }
        return producer;
    }

    @Override
    public synchronized void close() {
        if (producer != null) {
            producer.stop();
        }
        if (ownsCamel && camel != null) {
            camel.stop();
        }
    }
}
