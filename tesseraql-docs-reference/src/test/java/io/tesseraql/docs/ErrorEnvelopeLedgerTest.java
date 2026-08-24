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
 * The error-envelope ledger (docs/duplication-consolidation.md, campaign 3, the
 * {@code SqlExecutorLedgerTest} pattern): every main source file that spells the framework's
 * {@code {"error":…}} envelope as a string literal is named here. The campaign found seven
 * surfaces concatenating it by hand — each with its own or no escaping, one having drifted to a
 * message-less shape, one shipping the flat {@code {"error":"…"}} the federation endpoints had
 * already retired — and moved them onto {@code io.tesseraql.core.error.ErrorEnvelope}; this
 * ledger keeps them moved.
 *
 * <p><b>A new entry is refused by default.</b> An error body is built by
 * {@code ErrorResponseRenderer} where a route context exists, and by {@code ErrorEnvelope}
 * where one does not; a hand-spelled envelope must be argued here, in review, with a reason.
 * The deliberate non-envelope shapes — OAuth's RFC 6749 body and SCIM's RFC 7644 body — are
 * mapper-built against their specs and never spell this literal, so they need no entry.
 */
class ErrorEnvelopeLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-core/src/main/java/io/tesseraql/core/error/ErrorEnvelope.java"));

    @Test
    void everyHandSpelledEnvelopeIsOnTheLedger() throws IOException {
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
                            if (Files.readString(path).contains("{\\\"error\\\"")) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files spelling the {\"error\"…} envelope as a literal —"
                        + " build it with io.tesseraql.core.error.ErrorEnvelope (or the"
                        + " renderer, where a route context exists) instead, or add it here"
                        + " in review with a reason")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
