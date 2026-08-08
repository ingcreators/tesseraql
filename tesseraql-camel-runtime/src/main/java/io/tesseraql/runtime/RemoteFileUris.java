package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.connectors.FileConnectors;
import java.nio.file.Path;

/**
 * The one place remote file-endpoint URIs are assembled, shared by the {@code poll:} consumers
 * and the {@code push:} producer (docs/analytics-experience.md). Shared on purpose: the FTPS
 * data-channel settings drifted for a year when this logic had a single (consumer) home and a
 * lookalike copy — every transport and identity guarantee below must hold for both directions,
 * so both directions read it from here.
 *
 * <p>What it encodes: {@code RAW(...)} wrapping for every secret-bearing value (Camel must not
 * URL-decode them or split on an inner {@code &}), SFTP host-key pinning against the block's
 * {@code knownHostsFile}, FTPS {@code PBSZ 0}/{@code PROT P} data-channel protection plus
 * trust-store-backed server verification, and the exactly-one-authentication-method rule.
 */
final class RemoteFileUris {

    /** A remote poll source / push target declared without a credential (docs/connectors.md). */
    static final TqlErrorCode REMOTE_NEEDS_CREDENTIAL = new TqlErrorCode(TqlDomain.SEC, 4088);

    /** A connector credential declaring no authentication method, or more than one. */
    static final TqlErrorCode CREDENTIAL_METHOD = new TqlErrorCode(TqlDomain.SEC, 4089);

    private RemoteFileUris() {
    }

    /** {@code source} for the poll block, {@code target} for the push block — for messages. */
    private static String noun(FileConnectors connectors) {
        return "poll".equals(connectors.block()) ? "source" : "target";
    }

    /**
     * The scheme-authority-path-credential part every remote endpoint shares; {@code options}
     * carries the direction-specific consumer/producer settings and is appended verbatim.
     */
    static String remoteUri(String scheme, FileConnectors connectors, String host, Integer port,
            int defaultPort, String path, String credentialName, String options) {
        // A remote endpoint with no credential: was accepted, and produced a URI with no
        // username and no password. SFTP then fails at connect with a message about the server,
        // and FTPS may succeed as anonymous. Neither outcome tells the operator that the
        // declaration was incomplete.
        if (credentialName == null || credentialName.isBlank()) {
            throw new TqlException(REMOTE_NEEDS_CREDENTIAL, capitalize(connectors.block()) + " "
                    + noun(connectors) + " '" + scheme + "' needs a credential: declare one"
                    + " under tesseraql.connectors." + connectors.block()
                    + ".credentials and reference it with credential:");
        }
        FileConnectors.Credential credential = connectors.requireCredential(credentialName);
        int effectivePort = port == null ? defaultPort : port;
        // Camel's FTP/SFTP grammar: the first slash after the authority is a separator, a
        // second one makes the directory absolute. So the declared path passes through —
        // `outbound/orders` is login-home-relative, `/outbound/orders` absolute — instead of
        // the old double strip that silently made every path home-relative
        // (docs/contract-bugfixes.md track C). Extra leading slashes collapse to one, so the
        // historical `//` absolute-path escape keeps its meaning.
        String directory = path.replaceFirst("^/+", "/");
        StringBuilder uri = new StringBuilder(scheme).append("://")
                .append(host).append(':').append(effectivePort).append('/').append(directory)
                .append('?').append(options);
        // RAW(...) keeps Camel from URL-decoding a value with reserved characters, and keeps an
        // '&' inside one from splitting the query.
        uri.append("&username=RAW(").append(credential.require("username")).append(')')
                .append(authentication(scheme, connectors, credential));
        return uri.toString();
    }

    /**
     * Host-key verification for an SFTP endpoint: with the block's {@code knownHostsFile} set,
     * the server's SSH host key must match that known-hosts file (resolved against the app
     * home, like other configured file paths); without it, the key is not checked and lint
     * nudges with {@code TQL-SEC-4084}.
     */
    static String sftpHostKeyOptions(FileConnectors connectors, Path appHome) {
        return connectors.knownHostsFile()
                .map(file -> "&knownHostsFile="
                        + appHome.resolve(file).normalize().toAbsolutePath()
                        + "&strictHostKeyChecking=yes")
                .orElse("&strictHostKeyChecking=no");
    }

    /**
     * Transport settings for an FTPS endpoint, so it carries the same guarantees its SFTP
     * sibling does rather than only looking like it.
     *
     * <p>{@code PBSZ 0} + {@code PROT P} encrypt the <em>data</em> connection. Without them TLS
     * protects the control channel — the credentials — while every transferred file's bytes
     * cross the network in cleartext, which is what the previous
     * {@code disableSecureDataChannelDefaults} produced: that option reads like hardening and
     * does the opposite, suppressing the very defaults that would have negotiated protection.
     *
     * <p>{@code binary} and {@code passiveMode} both default to false in the component. ASCII
     * mode line-ending-translates payloads in transit, so an Excel or archive transfer arrives
     * corrupt; active mode asks the server to open a connection back to this process, which no
     * containerized or NAT'd deployment can accept.
     */
    static String ftpsTransportOptions(FileConnectors connectors, Path appHome, String host) {
        return "&execPbsz=0&execProt=P&binary=true&passiveMode=true"
                + ftpsTrustOptions(connectors, appHome, host);
    }

    /**
     * Server-identity verification for an FTPS endpoint, the counterpart of SFTP's known-hosts
     * check. With the block's {@code trustStore} declared, the server's certificate chain is
     * validated against that keystore and the hostname is checked.
     *
     * <p>Without it there is nothing to validate against: commons-net's default trust manager
     * only checks that the certificate is in date — no chain, no anchor, no hostname — so any
     * self-signed certificate from any host is accepted and TLS proves nothing about who
     * answered. The connection is therefore refused rather than run unverified, and lint says
     * so first ({@code TQL-SEC-4085}).
     */
    private static String ftpsTrustOptions(FileConnectors connectors, Path appHome,
            String host) {
        FileConnectors.TrustStore trust = connectors.trustStore().orElseThrow(
                () -> new IllegalArgumentException(capitalize(connectors.block()) + " "
                        + noun(connectors) + " 'ftps' for host '" + host
                        + "' needs tesseraql.connectors." + connectors.block()
                        + ".trustStore: without it the server certificate is not verified and"
                        + " TLS proves nothing about the peer"));
        StringBuilder options = new StringBuilder()
                .append("&ftpClient.trustStore.file=")
                .append(appHome.resolve(trust.file()).normalize().toAbsolutePath())
                .append("&ftpClient.endpointCheckingEnabled=true");
        if (trust.password() != null && !trust.password().isBlank()) {
            options.append("&ftpClient.trustStore.password=RAW(")
                    .append(trust.password()).append(')');
        }
        return options.toString();
    }

    /**
     * The credential's authentication method, of which there must be exactly one. Declaring
     * both {@code password:} and {@code privateKeyFile:} is refused rather than silently
     * preferring one: which one wins is exactly the sort of question a deployment should never
     * have to answer by experiment.
     */
    private static String authentication(String scheme, FileConnectors connectors,
            FileConnectors.Credential credential) {
        java.util.Optional<String> password = credential.setting("password");
        java.util.Optional<String> privateKey = credential.setting("privateKeyFile");
        if (password.isPresent() == privateKey.isPresent()) {
            throw new TqlException(CREDENTIAL_METHOD, capitalize(connectors.block())
                    + " credential '" + credential.name()
                    + "' needs exactly one of password: or privateKeyFile:, not "
                    + (password.isPresent() ? "both" : "neither"));
        }
        if (password.isPresent()) {
            // A password authenticates us; an FTPS client certificate can accompany it, since
            // mutual TLS and a login are different questions the server may ask together.
            return "&password=RAW(" + password.get() + ")"
                    + ("ftps".equals(scheme) ? ftpsClientCertificate(credential) : "");
        }
        if (!"sftp".equals(scheme)) {
            throw new TqlException(CREDENTIAL_METHOD, capitalize(connectors.block())
                    + " credential '" + credential.name()
                    + "' declares privateKeyFile:, which only an sftp endpoint can use");
        }
        return sftpKeyOptions(privateKey.get(), credential);
    }

    /**
     * An FTPS client certificate, when the credential declares a keystore — mutual TLS is how a
     * partner identifies <em>us</em>, and it accompanies a login rather than replacing it.
     */
    private static String ftpsClientCertificate(FileConnectors.Credential credential) {
        java.util.Optional<String> keyStore = credential.setting("keyStoreFile");
        if (keyStore.isEmpty()) {
            return "";
        }
        StringBuilder options = new StringBuilder("&ftpClient.keyStore.file=")
                .append(keyStore.get());
        credential.setting("keyStorePassword").ifPresent(password -> options
                .append("&ftpClient.keyStore.password=RAW(").append(password).append(')'));
        credential.setting("keyStoreType")
                .ifPresent(type -> options.append("&ftpClient.keyStore.type=").append(type));
        return options.toString();
    }

    private static String sftpKeyOptions(String privateKeyFile,
            FileConnectors.Credential credential) {
        StringBuilder key = new StringBuilder("&privateKeyFile=RAW(")
                .append(privateKeyFile).append(')');
        credential.setting("privateKeyPassphrase").ifPresent(passphrase -> key
                .append("&privateKeyPassphrase=RAW(").append(passphrase).append(')'));
        return key.toString();
    }

    private static String capitalize(String block) {
        return block.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + block.substring(1);
    }
}
