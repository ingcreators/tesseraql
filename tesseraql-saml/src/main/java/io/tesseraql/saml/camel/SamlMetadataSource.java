package io.tesseraql.saml.camel;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.http.HttpOutbound;
import io.tesseraql.yaml.manifest.AppManifest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@code tesseraql.saml.idp.metadata} setting to metadata bytes (docs/saml.md):
 * an app-home-relative file as before, or — new — an {@code https://} URL fetched at boot.
 * The metadata pins the IdP signing key, so a URL is held to the framework's egress
 * discipline: the host must be in {@code tesseraql.http.outbound.allowedHosts}
 * ({@code TQL-SEC-4086}, deny by default, the copilot-endpoint precedent) and plain
 * {@code http://} is refused off loopback ({@code TQL-SEC-4087}) — plaintext metadata would
 * be a key-injection channel. A successful fetch caches to {@code work/saml/idp-metadata.xml};
 * when the IdP's endpoint is unreachable at a later boot, the cached copy serves with a
 * warning, so an IdP outage never bricks the app.
 */
final class SamlMetadataSource {

    private static final TqlErrorCode HOST_DENIED = new TqlErrorCode(TqlDomain.SEC, 4086);
    private static final TqlErrorCode INSECURE_URL = new TqlErrorCode(TqlDomain.SEC, 4087);
    private static final Logger LOG = LoggerFactory.getLogger(SamlMetadataSource.class);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    private SamlMetadataSource() {
    }

    static byte[] load(AppManifest manifest, AppConfig config, String value) {
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            try {
                return Files.readAllBytes(manifest.appHome().resolve(value).normalize());
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read SAML IdP metadata: " + value, ex);
            }
        }
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (value.startsWith("http://") && !loopback) {
            throw new TqlException(INSECURE_URL, "SAML IdP metadata url '" + value
                    + "' must be https - the metadata pins the IdP signing key");
        }
        if (!HttpOutbound.load(config).isHostAllowed(host)) {
            throw new TqlException(HOST_DENIED, "SAML IdP metadata host '" + host
                    + "' is not in tesseraql.http.outbound.allowedHosts (deny by default)");
        }
        Path cache = manifest.appHome().resolve("work/saml/idp-metadata.xml");
        try {
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                    .connectTimeout(FETCH_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(HttpRequest.newBuilder(uri).timeout(FETCH_TIMEOUT).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("IdP metadata endpoint answered HTTP "
                        + response.statusCode());
            }
            Files.createDirectories(cache.getParent());
            Files.write(cache, response.body());
            return response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (Files.isRegularFile(cache)) {
                LOG.warn("SAML IdP metadata fetch from {} failed ({}); using the cached copy"
                        + " from the last successful fetch", value, ex.getMessage());
                try {
                    return Files.readAllBytes(cache);
                } catch (IOException unreadable) {
                    throw new IllegalStateException(
                            "Cached SAML IdP metadata is unreadable", unreadable);
                }
            }
            throw new IllegalStateException("Cannot fetch SAML IdP metadata from " + value
                    + " and no cached copy exists: " + ex.getMessage(), ex);
        }
    }
}
