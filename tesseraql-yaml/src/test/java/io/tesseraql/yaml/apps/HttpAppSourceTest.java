package io.tesseraql.yaml.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpAppSourceTest {

    static HttpServer server;
    static byte[] artifact;
    static String sha256;
    static final AtomicInteger fetches = new AtomicInteger();

    @TempDir
    Path workRoot;

    @BeforeAll
    static void start() throws Exception {
        artifact = tqlapp();
        sha256 = Hashing.sha256(artifact);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/orders.tqlapp", exchange -> {
            fetches.incrementAndGet();
            exchange.sendResponseHeaders(200, artifact.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(artifact);
            }
        });
        server.createContext("/missing.tqlapp", exchange -> exchange.sendResponseHeaders(404, -1));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Test
    void fetchesVerifiesAndCachesThePinnedArtifact() {
        HttpAppSource source = new HttpAppSource("orders", url("/orders.tqlapp"), sha256);
        int before = fetches.get();

        Path home = source.materialize(workRoot);
        assertThat(home.resolve("config/tesseraql.yml")).exists();
        assertThat(fetches.get()).isEqualTo(before + 1);

        // A restart reuses the verified download instead of fetching again.
        source.materialize(workRoot);
        assertThat(fetches.get()).isEqualTo(before + 1);
    }

    @Test
    void mismatchedHashFailsTheMount() {
        HttpAppSource source = new HttpAppSource(
                "tampered", url("/orders.tqlapp"), "0".repeat(64));
        assertThatThrownBy(() -> source.materialize(workRoot))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1212")
                .hasMessageContaining("pinned hash");
    }

    @Test
    void urlsWithoutAPinnedHashAreRejected() {
        assertThatThrownBy(() -> new HttpAppSource("orders", url("/orders.tqlapp"), null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1211")
                .hasMessageContaining("sha256");
    }

    @Test
    void httpErrorsFailLoudly() {
        HttpAppSource source = new HttpAppSource("gone", url("/missing.tqlapp"), sha256);
        assertThatThrownBy(() -> source.materialize(workRoot))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1212")
                .hasMessageContaining("404");
    }

    private static byte[] tqlapp() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("config/tesseraql.yml"));
            zip.write("tesseraql:\n  app:\n    name: orders\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
