package io.tesseraql.runtime;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.connectors.FileConnectors;
import io.tesseraql.yaml.model.PollSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Vector;

/**
 * A polled directory on a remote SFTP server (docs/camel-removal.md slice 1), over the same JSch
 * client the framework already shipped — it arrived under {@code camel-ftp}, which is why slice 1
 * declares it rather than continuing to borrow it. A borrowed type is a borrowed dependency.
 *
 * <p><strong>The settings are computed apart from the connection</strong> ({@link #settings}) so
 * that the rules worth pinning can be asserted without a server: host-key strictness, the
 * exactly-one-authentication-method rule, and the path grammar. They used to be asserted against
 * the endpoint URI this replaces, and they are the same rules with the same error codes.
 *
 * <p><strong>The URI is gone, and so is a class of defect with it.</strong> Every value that used
 * to need {@code RAW(...)} wrapping — a password with an {@code &} in it, a glob that looked like
 * another option — is now an argument to a method call. There is no query string for a secret to
 * be re-encoded in, and no place for an {@code include:} to smuggle an extra consumer option.
 */
final class SftpPollSource implements PollSource {

    private static final System.Logger LOG = System
            .getLogger(SftpPollSource.class.getName());

    /** How long to wait for the server, matching what the component asked for. */
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    /**
     * What it takes to reach one remote directory, resolved from configuration before anything
     * connects.
     *
     * @param privateKeyFile the identity file, or null when a password authenticates
     * @param password       the password, or null when a private key authenticates
     * @param knownHostsFile the file the server's host key must appear in, or null when unchecked
     */
    record Settings(String host, int port, String directory, String username, String password,
            String privateKeyFile, String privateKeyPassphrase, Path knownHostsFile) {

        /** Whether the server's identity is verified at all — false is the historical default. */
        boolean strictHostKeyChecking() {
            return knownHostsFile != null;
        }
    }

    private final Settings settings;
    private final Path workDirectory;
    private Session session;
    private ChannelSftp channel;

    SftpPollSource(Settings settings, Path workDirectory) {
        this.settings = settings;
        this.workDirectory = workDirectory;
    }

    /**
     * Resolves what a {@code poll:} block means for SFTP, refusing the same declarations the
     * endpoint URI refused.
     *
     * <p>The path grammar is unchanged and still the one docs/contract-bugfixes.md track C
     * settled: a leading slash means absolute, anything else is relative to the login home. JSch
     * resolves a relative path against the session's working directory, which is that home, so the
     * declaration passes through instead of being rewritten.
     */
    static Settings settings(FileConnectors connectors, PollSpec poll, Path appHome) {
        if (poll.credential() == null || poll.credential().isBlank()) {
            throw new TqlException(RemoteFileUris.REMOTE_NEEDS_CREDENTIAL,
                    "Poll source 'sftp' needs a credential: declare one under"
                            + " tesseraql.connectors.poll.credentials and reference it with"
                            + " credential:");
        }
        FileConnectors.Credential credential = connectors.requireCredential(poll.credential());
        Optional<String> password = credential.setting("password");
        Optional<String> privateKey = credential.setting("privateKeyFile");
        if (password.isPresent() == privateKey.isPresent()) {
            throw new TqlException(RemoteFileUris.CREDENTIAL_METHOD, "Poll credential '"
                    + credential.name() + "' needs exactly one of password: or privateKeyFile:,"
                    + " not " + (password.isPresent() ? "both" : "neither"));
        }
        return new Settings(poll.host(), poll.port() == null ? 22 : poll.port(),
                directory(poll.path()),
                credential.require("username"), password.orElse(null), privateKey.orElse(null),
                credential.setting("privateKeyPassphrase").orElse(null),
                connectors.knownHostsFile()
                        .map(file -> appHome.resolve(file).normalize().toAbsolutePath())
                        .orElse(null));
    }

    /**
     * The remote directory as declared, with the historical escape collapsed.
     *
     * <p>A leading {@code //} used to be how an absolute path survived Camel's URI grammar, so it
     * still means what it meant — one slash, absolute — rather than becoming a path with an empty
     * first segment.
     */
    private static String directory(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }
        return path.replaceFirst("^/+", "/");
    }

    @Override
    public List<PolledFile> list() throws IOException {
        ChannelSftp sftp = connected();
        List<PolledFile> files = new ArrayList<>();
        try {
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(settings.directory());
            for (ChannelSftp.LsEntry entry : entries) {
                SftpATTRS attributes = entry.getAttrs();
                if (attributes.isDir() || attributes.isLink()) {
                    continue;
                }
                files.add(new PolledFile(entry.getFilename(), attributes.getSize(),
                        attributes.getMTime() * 1000L));
            }
        } catch (SftpException ex) {
            throw failure("list " + settings.directory(), ex);
        }
        files.sort(Comparator.comparing(PolledFile::name));
        return files;
    }

    @Override
    public Optional<PolledFile> stat(String name) throws IOException {
        try {
            SftpATTRS attributes = connected().stat(remote(name));
            return Optional.of(new PolledFile(name, attributes.getSize(),
                    attributes.getMTime() * 1000L));
        } catch (SftpException gone) {
            return Optional.empty();
        }
    }

    /**
     * Downloads the file under the work directory rather than handing over a network stream.
     *
     * <p>The remote components default to loading the whole file into memory before a route sees
     * it, which is why the endpoint asked for a {@code localWorkDirectory}. Downloading here keeps
     * that promise for the same reason: the spool that follows is a disk-to-disk copy, a large
     * file never materialises in heap, and the remote file is still there to be retried or moved.
     */
    @Override
    public Fetched fetch(PolledFile file) throws IOException {
        Files.createDirectories(workDirectory);
        Path target = workDirectory.resolve(file.name());
        try {
            connected().get(remote(file.name()), target.toString());
        } catch (SftpException ex) {
            throw failure("download " + file.name(), ex);
        }
        return new Fetched(target, true);
    }

    @Override
    public void archive(PolledFile file, String subDirectory) throws IOException {
        ChannelSftp sftp = connected();
        String directory = settings.directory() + "/" + subDirectory;
        try {
            ensureDirectory(sftp, directory);
            String target = directory + "/" + file.name();
            // The server refuses a rename onto an existing name, so the previous delivery of the
            // same file name gives way — the archive directory keeps the latest, as it does
            // locally, rather than the poll failing on the second file of the same name.
            try {
                sftp.rm(target);
            } catch (SftpException absent) {
                LOG.log(System.Logger.Level.TRACE, "No previous {0} to replace", target);
            }
            sftp.rename(remote(file.name()), target);
        } catch (SftpException ex) {
            throw failure("archive " + file.name() + " to " + subDirectory, ex);
        }
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    private String remote(String name) {
        return settings.directory() + "/" + name;
    }

    private static void ensureDirectory(ChannelSftp sftp, String directory) throws SftpException {
        try {
            sftp.stat(directory);
        } catch (SftpException absent) {
            sftp.mkdir(directory);
        }
    }

    /**
     * The connected channel, reconnecting when the server has dropped us.
     *
     * <p>One session held across cycles rather than one per poll: a 500ms poll interval against a
     * handshake per cycle is a denial of service on the partner's server, and the reconnect below
     * is what makes holding it safe.
     */
    private ChannelSftp connected() throws IOException {
        if (channel != null && channel.isConnected() && session != null && session.isConnected()) {
            return channel;
        }
        close();
        try {
            JSch jsch = new JSch();
            if (settings.privateKeyFile() != null) {
                // The byte[] overloads rather than the String ones: JSch deprecated the latter
                // because a passphrase in a String cannot be cleared, and it keeps a copy.
                jsch.addIdentity(settings.privateKeyFile(),
                        settings.privateKeyPassphrase() == null
                                ? null
                                : settings.privateKeyPassphrase()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (settings.knownHostsFile() != null) {
                jsch.setKnownHosts(settings.knownHostsFile().toString());
            }
            session = jsch.getSession(settings.username(), settings.host(), settings.port());
            if (settings.password() != null) {
                session.setPassword(
                        settings.password().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // Without a known-hosts file there is nothing to check the server against, and this
            // says so rather than failing every connection. Lint is where an operator is told;
            // the historical behaviour is what keeps existing apps polling.
            session.setConfig("StrictHostKeyChecking",
                    settings.strictHostKeyChecking() ? "yes" : "no");
            session.connect(CONNECT_TIMEOUT_MILLIS);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CONNECT_TIMEOUT_MILLIS);
            return channel;
        } catch (JSchException ex) {
            close();
            throw new IOException("Could not reach sftp://" + settings.host() + ":"
                    + settings.port() + " — " + ex.getMessage(), ex);
        }
    }

    private IOException failure(String what, SftpException ex) {
        return new IOException("Could not " + what + " on sftp://" + settings.host() + ":"
                + settings.port() + " — " + ex.getMessage(), ex);
    }
}
