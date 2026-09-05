package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The work-directory ledger (docs/http-edge-robustness.md decision 7, following the
 * {@code AppNameReadLedgerTest} precedent): every main source file that builds a path under
 * {@code work/} by hand instead of resolving through
 * {@code io.tesseraql.yaml.config.WorkHome}.
 *
 * <p>{@code WorkHome}'s own javadoc states the contract this guards — {@code tesseraql.app.work}
 * is "honored everywhere or nowhere; a half-honored relocation key is exactly the
 * emitted-but-dead class this closes". It is currently half-honored. The key is scaffolded into
 * every application with {@code WorkHome} registered as its consumer, and the sites below
 * resolve the conventional layout directly from an application home, so declaring the key moves
 * some of an application's state and leaves the rest behind.
 *
 * <p><b>This ledger is shrink-only.</b> It exists because the HTTP-edge campaign repaired one of
 * these sites — the temp-store scratch, which since #1149 also carries the request-body upload
 * spool and is created as a boot precondition — and repairing one instance of a class silently
 * is how the class survives. Every remaining entry is a recorded gap, not an approval: each one
 * is a place where {@code tesseraql.app.work} does not do what the key promises. Removing an
 * entry by routing it through {@code WorkHome} is the intended direction of travel; adding one
 * fails this test.
 *
 * <p>Two entries are not gaps and stay permanently:
 *
 * <ul>
 * <li>{@code WorkHome} — the resolver itself, which is where the conventional default lives.</li>
 * <li>{@code MultiAppHost} — resolves from the stack's install root rather than from an
 * application home, materializing the portal surface beside the members. There is no
 * application whose key could relocate it.</li>
 * </ul>
 */
class WorkHomeLedgerTest {

    private static final Path REPO = Path.of("..");

    /**
     * A path literal under {@code work/} handed to {@code resolve}. The conventional layout is
     * the only thing spelled this way; a configured directory arrives as a key's value.
     */
    private static final Pattern HAND_ROLLED = Pattern.compile("\\.resolve\\(\"work[/\"]");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            // Permanent: the resolver, and a path with no application to relocate it.
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/WorkHome.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/MultiAppHost.java",
            // Recorded gaps. Each is state an application owns, under a directory
            // tesseraql.app.work claims to move, that the key does not move today.
            "tesseraql-cli/src/main/java/io/tesseraql/cli/DuckDbCommand.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DuckDbDatasources.java",
            "tesseraql-saml/src/main/java/io/tesseraql/saml/routes/SamlMetadataSource.java",
            "tesseraql-studio/src/main/java/io/tesseraql/studio/AuditTrail.java",
            "tesseraql-studio/src/main/java/io/tesseraql/studio/DraftStore.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/blob/BlobStores.java"));

    @Test
    void everyHandRolledWorkPathIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // A .claude/ worktree on disk carries stale copies of exactly these files.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            if (HAND_ROLLED.matcher(Files.readString(path)).find()) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files resolving a work/ path by hand — tesseraql.app.work is"
                        + " honored everywhere or nowhere, so resolve through"
                        + " io.tesseraql.yaml.config.WorkHome instead. This ledger is"
                        + " shrink-only: a new entry is a new place the relocation key lies")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
