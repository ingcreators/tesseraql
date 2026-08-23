package io.tesseraql.saml.routes;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@code tesseraql.saml.idp.metadata} setting to metadata bytes (docs/saml.md):
 * an app-home-relative file, or an {@code https://} URL fetched at boot through the runtime's
 * {@link OutboundGateway} (docs/duplication-consolidation.md, campaign 1) — the metadata pins
 * the IdP signing key, so a URL is held to the framework's egress discipline: the deny-by-default
 * allow-list, the timeouts, the circuit breaker, and the {@code tesseraql.http.call} span, like
 * every other outbound call. Plain {@code http://} is refused off loopback ({@code TQL-SEC-4087})
 * — plaintext metadata would be a key-injection channel. A successful fetch caches to
 * {@code work/saml/idp-metadata.xml}; when the IdP's endpoint is unreachable at a later boot, the
 * cached copy serves with a warning, so an IdP outage never bricks the app.
 */
final class SamlMetadataSource {

    private static final TqlErrorCode INSECURE_URL = new TqlErrorCode(TqlDomain.SEC, 4087);
    private static final Logger LOG = LoggerFactory.getLogger(SamlMetadataSource.class);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    private SamlMetadataSource() {
    }

    static byte[] load(AppManifest manifest, OutboundGateway gateway, String value) {
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
        Path cache = manifest.appHome().resolve("work/saml/idp-metadata.xml");
        try {
            String timeout = FETCH_TIMEOUT.toMillis() + "ms";
            OutboundGateway.RawResponse response = gateway.exchange(
                    new HttpCallSpec("GET", value, Map.of(), null, null, null, null,
                            timeout, timeout),
                    null, Map.of());
            if (response.status() != 200) {
                throw new IOException("IdP metadata endpoint answered HTTP "
                        + response.status());
            }
            Files.createDirectories(cache.getParent());
            Files.write(cache, response.body());
            return response.body();
        } catch (IOException | TqlException ex) {
            // The gateway's policy refusal is a configuration to fix, never something a cached
            // copy may paper over; only an unreachable endpoint falls back to the cache.
            if (ex instanceof TqlException refused
                    && io.tesseraql.yaml.http.HttpOutbound.HOST_DENIED.equals(refused.code())) {
                throw refused;
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
