package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The cause-chain ledger (docs/audit-low-leads.md slice 16, F107): a {@code catch} of a
 * {@code SQLException} that builds a {@code TqlException} chains the caught exception into it.
 * Twenty-six sites wrapped with the message alone, so the stack every 5xx logs since the
 * error-hygiene work ended at the wrap site with no {@code Caused by:}, and one CLI verb
 * ({@code schema}) answered a refused database with a coded line at exit 2 where the handler's
 * cause-walk would have shaped the operator message at 1 (docs/cli-surface.md decision 10).
 *
 * <p>The {@code SQLException} class is the one policed: it is the informative type — SQLState,
 * vendor code, the driver's own chain — and the one a consumer walks. Wraps of other types are
 * not judged here.
 *
 * <p><b>A new entry is refused by default.</b> Chain the cause ({@code new TqlException(code,
 * message, ex)} or {@code .cause(ex)}); a wrap that must not carry it says so here, in review,
 * with a reason. Removing an entry just shrinks the list.
 */
class CauseChainLedgerTest {

    private static final Path REPO = Path.of("..");

    /** Wrap sites allowed to drop the cause, with the reason each was admitted. */
    private static final Set<String> LEDGER = new TreeSet<>(List.of());

    private static final Pattern CATCH = Pattern.compile(
            "catch\\s*\\(\\s*((?:[\\w.]+\\s*\\|\\s*)*[\\w.]+)\\s+(\\w+)\\s*\\)\\s*\\{");

    @Test
    void everySqlExceptionWrapCarriesItsCauseOrIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on
                    // disk carries stale copies of exactly the files this ledger reads.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            String rel = REPO.relativize(path).toString().replace('\\', '/');
                            for (int line : droppedCauses(Files.readString(path))) {
                                found.add(rel + ":" + line);
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("catch (SQLException ex) blocks that build a TqlException without chaining"
                        + " ex - pass it as the cause (the three-argument constructor or"
                        + " .cause(ex)), or add the site here in review with a reason; a"
                        + " REMOVED entry just shrinks this list")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }

    /** The line of every SQLException catch whose body builds a TqlException without the cause. */
    static List<Integer> droppedCauses(String source) {
        List<Integer> dropped = new java.util.ArrayList<>();
        Matcher caught = CATCH.matcher(source);
        while (caught.find()) {
            if (!caught.group(1).contains("SQLException")) {
                continue;
            }
            String variable = caught.group(2);
            int end = blockEnd(source, caught.end());
            String body = source.substring(caught.end(), end);
            if (!body.contains("TqlException")) {
                continue;
            }
            boolean chained = Pattern.compile(
                    ",\\s*" + variable + "\\s*\\)|\\.cause\\(\\s*" + variable + "\\s*\\)"
                            + "|throw\\s+" + variable + "\\s*;|" + variable + "\\.addSuppressed")
                    .matcher(body).find();
            if (!chained) {
                dropped.add(lineOf(source, caught.start()));
            }
        }
        return dropped;
    }

    private static int blockEnd(String source, int from) {
        int depth = 1;
        int i = from;
        while (i < source.length() && depth > 0) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        return i - 1;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
