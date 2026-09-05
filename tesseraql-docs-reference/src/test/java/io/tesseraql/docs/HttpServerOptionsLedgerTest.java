package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The server-options ledger (docs/http-edge-robustness.md decision 12, following
 * {@code HttpClientLedgerTest}): every main source file that builds a Vert.x
 * {@code HttpServerOptions}.
 *
 * <p>The defect class this guards is the one the edge already names out loud about the body
 * limit — "the transport upgrade to Vert.x 5 changed that default from unlimited to 10 MB,
 * silently — a borrowed bound nothing declared". Two sockets in this framework terminate a
 * client connection, and every bound they do not set is Vert.x's. Which files are allowed to
 * build one is therefore a reviewed decision: a third means a third set of inherited defaults,
 * arriving with no reason given.
 *
 * <ul>
 * <li>{@code TesseraqlHttpServer} — a runtime's own port, whose declared bounds live in
 * {@code serverOptions(HttpEdgeSettings)}.</li>
 * <li>{@code StackRelay} — the gateway's front door, the socket a client actually connects to
 * under {@code tesseraql host}.</li>
 * </ul>
 *
 * <p>This ledger pins the <em>sites</em>, not the values. What each site declares is pinned by
 * {@code HttpEdgeDefaultsTest} in tesseraql-runtime, beside the code it guards, because these
 * classes are package-private and a values test that could reach them from here would have to
 * widen them.
 */
class HttpServerOptionsLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/StackRelay.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/TesseraqlHttpServer.java"));

    @Test
    void everyServerOptionsConstructionSiteIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on
                    // disk carries stale copies of exactly the files this ledger greps.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            if (Files.readString(path).contains("new HttpServerOptions(")) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files building HttpServerOptions — a NEW entry is a third"
                        + " server socket inheriting Vert.x's defaults for every bound it does"
                        + " not set; declare them where the existing two do, or add the file"
                        + " here in review with a reason")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
