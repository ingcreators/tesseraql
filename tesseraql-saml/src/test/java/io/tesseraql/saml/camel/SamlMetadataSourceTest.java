package io.tesseraql.saml.camel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IdP metadata over a URL (docs/saml.md): fetched at boot under the egress allow-list, cached
 * for the next boot's outage, https-only off loopback.
 */
class SamlMetadataSourceTest {

    private static AppManifest app(Path dir, String allowedHost) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  http:
                    outbound:
                      allowedHosts:
                        - %s
                """.formatted(allowedHost));
        return new ManifestLoader().load(dir);
    }

    @Test
    void fetchesCachesAndFallsBackWhenTheIdpIsDown(@TempDir Path dir) throws Exception {
        byte[] xml = "<EntityDescriptor/>".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean up = new AtomicBoolean(true);
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/metadata", exchange -> {
            if (!up.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, xml.length);
            exchange.getResponseBody().write(xml);
            exchange.close();
        });
        idp.start();
        try {
            AppManifest manifest = app(dir, "127.0.0.1");
            String url = "http://127.0.0.1:" + idp.getAddress().getPort() + "/metadata";

            assertThat(SamlMetadataSource.load(manifest, manifest.config(), url)).isEqualTo(xml);
            assertThat(dir.resolve("work/saml/idp-metadata.xml")).exists();

            // The IdP goes down: the cached copy serves, so the boot survives the outage.
            up.set(false);
            assertThat(SamlMetadataSource.load(manifest, manifest.config(), url)).isEqualTo(xml);
        } finally {
            idp.stop(0);
        }
    }

    @Test
    void aDeniedHostAndAPlainHttpUrlOffLoopbackAreRefused(@TempDir Path dir) throws Exception {
        AppManifest manifest = app(dir, "idp.example.com");
        assertThatThrownBy(() -> SamlMetadataSource.load(manifest, manifest.config(),
                "https://other.example.com/metadata"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4086");
        assertThatThrownBy(() -> SamlMetadataSource.load(manifest, manifest.config(),
                "http://idp.example.com/metadata"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4087");
    }

    @Test
    void aRelativePathStaysAPlainFileRead(@TempDir Path dir) throws Exception {
        AppManifest manifest = app(dir, "idp.example.com");
        Files.writeString(dir.resolve("idp.xml"), "<EntityDescriptor/>");
        assertThat(SamlMetadataSource.load(manifest, manifest.config(), "idp.xml"))
                .asString(StandardCharsets.UTF_8).contains("EntityDescriptor");
    }
}
