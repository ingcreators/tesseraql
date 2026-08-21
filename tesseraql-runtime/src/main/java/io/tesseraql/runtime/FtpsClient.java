package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.connectors.FileConnectors;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

/**
 * An FTPS server, over the commons-net client the framework already shipped
 * (docs/camel-removal.md decision 4).
 *
 * <p>Every transport setting here was one endpoint option long, and each is the difference between
 * FTPS and something that only looks like it. They are asserted in {@link FtpsClientTest} against
 * the settings the client is configured from, because they were asserted against a URI string
 * before — and the data channel stayed unencrypted for a year while that string looked right.
 *
 * <ul>
 *   <li><strong>{@code PBSZ 0} then {@code PROT P}</strong> encrypt the <em>data</em> connection.
 *       Without them TLS protects the control channel — the credentials — while every transferred
 *       file's bytes cross the network in cleartext.</li>
 *   <li><strong>A trust store, or the connection is refused.</strong> There is nothing else to
 *       validate the server against: the default trust manager checks only that a certificate is
 *       in date, so any self-signed certificate from any host would be accepted and the handshake
 *       would prove nothing about who answered. Hostname verification rides with it.</li>
 *   <li><strong>Binary mode and passive mode.</strong> ASCII mode line-ending-translates an Excel
 *       or archive payload in transit; active mode asks the server to open a connection back to
 *       this process, which no containerised or NAT'd deployment can accept.</li>
 * </ul>
 */
final class FtpsClient implements RemoteFiles {

    /** What it takes to reach one FTPS server, resolved before anything connects. */
    record Settings(String host, int port, String directory, String username, String password,
            Path trustStore, String trustStorePassword, String keyStore, String keyStorePassword,
            String keyStoreType) {
    }

    private final Settings settings;
    private FTPSClient client;

    FtpsClient(Settings settings) {
        this.settings = settings;
    }

    /**
     * Resolves what a {@code poll:} or {@code push:} block means for FTPS, refusing the same
     * declarations the endpoint URI refused.
     */
    static Settings settings(FileConnectors connectors, String host, Integer port, String path,
            String credentialName, Path appHome) {
        if (credentialName == null || credentialName.isBlank()) {
            throw new TqlException(RemoteFiles.REMOTE_NEEDS_CREDENTIAL,
                    capitalize(connectors.block()) + " " + noun(connectors) + " 'ftps' needs a"
                            + " credential: declare one under tesseraql.connectors."
                            + connectors.block()
                            + ".credentials and reference it with credential:");
        }
        FileConnectors.Credential credential = connectors.requireCredential(credentialName);
        Optional<String> password = credential.setting("password");
        Optional<String> privateKey = credential.setting("privateKeyFile");
        if (privateKey.isPresent()) {
            throw new TqlException(RemoteFiles.CREDENTIAL_METHOD, capitalize(
                    connectors.block()) + " credential '" + credential.name()
                    + "' declares privateKeyFile:, which only an sftp endpoint can use");
        }
        if (password.isEmpty()) {
            throw new TqlException(RemoteFiles.CREDENTIAL_METHOD, capitalize(
                    connectors.block()) + " credential '" + credential.name()
                    + "' needs exactly one of password: or privateKeyFile:, not neither");
        }
        FileConnectors.TrustStore trust = connectors.trustStore().orElseThrow(
                () -> new IllegalArgumentException(capitalize(connectors.block()) + " "
                        + noun(connectors) + " 'ftps' for host '" + host
                        + "' needs tesseraql.connectors." + connectors.block()
                        + ".trustStore: without it the server certificate is not verified and"
                        + " TLS proves nothing about the peer"));
        return new Settings(host, port == null ? 21 : port, directory(path),
                credential.require("username"), password.get(),
                appHome.resolve(trust.file()).normalize().toAbsolutePath(), trust.password(),
                credential.setting("keyStoreFile").orElse(null),
                credential.setting("keyStorePassword").orElse(null),
                credential.setting("keyStoreType").orElse(null));
    }

    /** The remote directory as declared, with the historical {@code //} escape collapsed. */
    private static String directory(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }
        return path.replaceFirst("^/+", "/");
    }

    @Override
    public java.util.List<PollSource.PolledFile> list() throws IOException {
        java.util.List<PollSource.PolledFile> files = new java.util.ArrayList<>();
        for (FTPFile file : connected().listFiles(settings.directory())) {
            if (file == null || !file.isFile()) {
                continue;
            }
            files.add(new PollSource.PolledFile(file.getName(), file.getSize(),
                    file.getTimestamp() == null ? 0L : file.getTimestamp().getTimeInMillis()));
        }
        return files;
    }

    @Override
    public Optional<PollSource.PolledFile> stat(String name) throws IOException {
        FTPFile[] found = connected().listFiles(settings.directory() + "/" + name);
        if (found.length == 0 || found[0] == null || !found[0].isFile()) {
            return Optional.empty();
        }
        FTPFile file = found[0];
        return Optional.of(new PollSource.PolledFile(name, file.getSize(),
                file.getTimestamp() == null ? 0L : file.getTimestamp().getTimeInMillis()));
    }

    @Override
    public void download(String name, Path target) throws IOException {
        String remote = settings.directory() + "/" + name;
        try (OutputStream out = Files.newOutputStream(target)) {
            if (!connected().retrieveFile(remote, out)) {
                throw new IOException("The server refused to send " + remote + ": "
                        + connected().getReplyString().trim());
            }
        }
    }

    /**
     * Uploads under a dot-name and renames on completion.
     *
     * <p>The remote twin of the local {@code .part} dance: a partner's poller must never read a
     * file this one is still writing.
     */
    @Override
    public void upload(String filename, InputStream content) throws IOException {
        String temporary = settings.directory() + "/.uploading-" + filename;
        String target = settings.directory() + "/" + filename;
        FTPSClient ftps = connected();
        if (!ftps.storeFile(temporary, content)) {
            throw new IOException("The server refused the upload: " + ftps.getReplyString().trim());
        }
        ftps.deleteFile(target);
        if (!ftps.rename(temporary, target)) {
            throw new IOException("The server refused to rename the upload into place: "
                    + ftps.getReplyString().trim());
        }
    }

    @Override
    public void archive(String name, String subDirectory) throws IOException {
        FTPSClient ftps = connected();
        String directory = settings.directory();
        String target = directory + "/" + subDirectory;
        if (ftps.listFiles(target).length == 0 && !ftps.changeWorkingDirectory(target)) {
            ftps.makeDirectory(target);
        }
        ftps.deleteFile(target + "/" + name);
        if (!ftps.rename(directory + "/" + name, target + "/" + name)) {
            throw new IOException("The server refused to move " + name + " to " + subDirectory
                    + ": " + ftps.getReplyString().trim());
        }
    }

    @Override
    public void close() {
        FTPSClient open = client;
        client = null;
        if (open == null) {
            return;
        }
        try {
            if (open.isConnected()) {
                open.logout();
                open.disconnect();
            }
        } catch (IOException ignored) {
            // The connection is being abandoned either way.
        }
    }

    /** The connected client, reconnecting when the server has dropped us. */
    private FTPSClient connected() throws IOException {
        if (client != null && client.isConnected()) {
            return client;
        }
        close();
        // Explicit FTPS (AUTH TLS on the control port), which is what ftps:// meant.
        FTPSClient ftps = new FTPSClient(false, sslContext());
        // The hostname is checked against the certificate, which is the half a trust store does
        // not do on its own: a valid certificate for another host would otherwise be accepted.
        // This is the option the endpoint URI spelled as ftpClient.endpointCheckingEnabled=true.
        ftps.setEndpointCheckingEnabled(true);
        try {
            ftps.connect(settings.host(), settings.port());
            if (!FTPReply.isPositiveCompletion(ftps.getReplyCode())) {
                throw new IOException("The server refused the connection: "
                        + ftps.getReplyString().trim());
            }
            if (!ftps.login(settings.username(), settings.password())) {
                throw new IOException("The server refused the login: "
                        + ftps.getReplyString().trim());
            }
            // The data channel, encrypted. Without this pair TLS covers the login and nothing
            // else, and every transferred byte crosses the network in the clear.
            ftps.execPBSZ(0);
            ftps.execPROT("P");
            ftps.setFileType(FTP.BINARY_FILE_TYPE);
            ftps.enterLocalPassiveMode();
            client = ftps;
            return ftps;
        } catch (IOException failed) {
            try {
                ftps.disconnect();
            } catch (IOException ignored) {
                // Reporting the original failure matters more than this one.
            }
            throw failed;
        }
    }

    /** The trust store the server is validated against, and the client certificate, if declared. */
    private SSLContext sslContext() throws IOException {
        try {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream in = Files.newInputStream(settings.trustStore())) {
                trust.load(in, settings.trustStorePassword() == null
                        ? null
                        : settings.trustStorePassword().toCharArray());
            }
            TrustManagerFactory trustManagers = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);

            KeyManagerFactory keyManagers = null;
            if (settings.keyStore() != null) {
                KeyStore identity = KeyStore.getInstance(settings.keyStoreType() == null
                        ? KeyStore.getDefaultType()
                        : settings.keyStoreType());
                char[] password = settings.keyStorePassword() == null
                        ? null
                        : settings.keyStorePassword().toCharArray();
                try (InputStream in = Files.newInputStream(Path.of(settings.keyStore()))) {
                    identity.load(in, password);
                }
                keyManagers = KeyManagerFactory
                        .getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(identity, password);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(), null);
            return context;
        } catch (java.security.GeneralSecurityException unusable) {
            throw new IOException("The FTPS trust material could not be loaded: "
                    + unusable.getMessage(), unusable);
        }
    }

    private static String noun(FileConnectors connectors) {
        return "poll".equals(connectors.block()) ? "source" : "target";
    }

    private static String capitalize(String block) {
        return block.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + block.substring(1);
    }
}
