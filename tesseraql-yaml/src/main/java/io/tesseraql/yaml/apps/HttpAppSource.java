package io.tesseraql.yaml.apps;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Mounts a {@code .tqlapp} fetched from a URL (design ch. 32, 47) - the declarative distribution
 * for multi-server deployments: the configuration names the artifact and pins its SHA-256, every
 * node fetches and verifies the same bytes at boot, and a mismatch fails the mount instead of
 * silently running drifted code.
 *
 * <pre>
 * tesseraql:
 *   apps:
 *     orders:
 *       url: https://artifacts.example.com/orders-1.2.0.tqlapp
 *       sha256: 4f2a...   # required - remote content is never mounted unpinned
 * </pre>
 *
 * <p>The download lands under the work root and is reused on restart while its hash still
 * matches, so a node can reboot without the artifact store being reachable.
 */
public final class HttpAppSource implements AppSource {

    private static final System.Logger LOG = System.getLogger(HttpAppSource.class.getName());
    private static final TqlErrorCode UNPINNED = new TqlErrorCode(TqlDomain.YAML, 1211);
    private static final TqlErrorCode DOWNLOAD_FAILED = new TqlErrorCode(TqlDomain.YAML, 1212);

    private final String name;
    private final URI url;
    private final String expectedSha256;

    public HttpAppSource(String name, String url, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new TqlException(UNPINNED, "App '" + name + "' is fetched from a URL and must"
                    + " pin its content: set tesseraql.apps." + name + ".sha256");
        }
        this.name = name;
        this.url = URI.create(url);
        this.expectedSha256 = expectedSha256.toLowerCase(Locale.ROOT);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Path materialize(Path workRoot) {
        Path downloads = workRoot.resolve("downloads");
        Path artifact = downloads.resolve(name + ".tqlapp");
        try {
            Files.createDirectories(downloads);
            if (!Files.isRegularFile(artifact)
                    || !Hashing.sha256(artifact).equalsIgnoreCase(expectedSha256)) {
                download(artifact);
            } else {
                LOG.log(System.Logger.Level.INFO,
                        "App ''{0}'' already downloaded with the pinned hash; skipping fetch",
                        name);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // The zip mount re-verifies the hash and extracts traversal-safely.
        return new ZipAppSource(name, artifact, expectedSha256).materialize(workRoot);
    }

    private void download(Path artifact) throws IOException {
        LOG.log(System.Logger.Level.INFO, "Fetching app ''{0}'' from {1}", name, url);
        Path temp = Files.createTempFile(artifact.getParent(), name, ".download");
        try {
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder(url).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new TqlException(DOWNLOAD_FAILED, "App '" + name + "' download from "
                        + url + " failed with HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = Hashing.sha256(temp);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new TqlException(DOWNLOAD_FAILED, "App '" + name + "' downloaded from "
                        + url + " does not match the pinned hash (expected " + expectedSha256
                        + ", got " + actual + ")");
            }
            Files.move(temp, artifact, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TqlException(DOWNLOAD_FAILED, "App '" + name + "' download interrupted");
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
