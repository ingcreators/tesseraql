package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No merge-conflict markers reach a committed file.
 *
 * <p>A rebase left {@code <<<<<<< HEAD} in the middle of a design document's slice list and it
 * merged, because nothing looks at prose. Java would not have compiled and YAML would not have
 * parsed, so markdown is precisely where this can land silently — and a design document with a
 * conflict marker in it is one nobody trusts the rest of.
 *
 * <p>The check is over tracked text files rather than a directory list: the next place a marker
 * lands is the one that was not on the list.
 */
class NoConflictMarkersTest {

    private static final Path REPO = Path.of("..");

    /** Split so this file's own scanner does not match itself. */
    private static final String OURS = "<<<<<<" + "< ";
    private static final String THEIRS = ">>>>>>" + "> ";
    private static final String SEPARATOR = "======" + "=";

    private static final List<String> SCANNED = List.of(
            ".md", ".java", ".yml", ".yaml", ".json", ".sql", ".html", ".ts", ".mjs", ".xml");

    @Test
    void noTrackedFileCarriesAConflictMarker() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = file.toString().replace('\\', '/');
                if (path.contains("/target/") || path.contains("/node_modules/")
                        || path.contains("/.git/")
                        || path.endsWith("NoConflictMarkersTest.java")) {
                    continue;
                }
                if (SCANNED.stream().noneMatch(path::endsWith)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file,
                        java.nio.charset.StandardCharsets.UTF_8);
                // The separator alone is also how markdown underlines a setext heading, so it
                // only counts in a file that opens a conflict. The other two are unambiguous.
                boolean conflicted = lines.stream().anyMatch(l -> l.startsWith(OURS));
                for (int line = 0; line < lines.size(); line++) {
                    String content = lines.get(line);
                    if (content.startsWith(OURS) || content.startsWith(THEIRS)
                            || (conflicted && content.equals(SEPARATOR))) {
                        offenders.add(path + ":" + (line + 1));
                    }
                }
            }
        } catch (java.io.UncheckedIOException unreadable) {
            // A file this test cannot read is not a file it can clear, so say so rather than pass.
            throw new AssertionError("Could not scan the repository for conflict markers",
                    unreadable);
        }

        assertThat(offenders).as("committed merge-conflict markers").isEmpty();
    }
}
