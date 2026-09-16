package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every bundled system app's {@code .app-index} names exactly the files beside it.
 *
 * <p>A jar cannot be walked, so {@code ClasspathAppSource} extracts a bundled app by its
 * authored index and serves what the index names. A file added to the tree and not to the
 * index is on the classpath and nowhere else: a route document is never mounted and answers
 * 404, a template is never found — silently, because nothing reads the directory. Slice 4 of
 * {@code docs/audit-low-leads.md} shipped IAM Admin's withdraw route that way for one test run;
 * this makes the omission loud. The other direction, an index entry without a file, already
 * fails the boot ({@code TQL-YAML-1204}), and is checked here too so the two lists cannot drift
 * either way.
 */
class BundledAppIndexLedgerTest {

    private static final Path REPO = Path.of("..");

    @Test
    void everyBundledAppIndexNamesExactlyTheFilesBesideIt() throws IOException {
        List<Path> indexes = new java.util.ArrayList<>();
        try (Stream<Path> modules = Files.list(REPO)) {
            for (Path module : modules.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("tesseraql-"))
                    .sorted().toList()) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(resources)) {
                    files.filter(path -> path.getFileName().toString().equals(".app-index"))
                            .sorted().forEach(indexes::add);
                }
            }
        }
        assertThat(indexes).as("bundled app indexes under src/main/resources").isNotEmpty();
        for (Path index : indexes) {
            Path app = index.getParent();
            Set<String> listed = new TreeSet<>();
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String entry = line.strip();
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    listed.add(entry);
                }
            }
            Set<String> present = new TreeSet<>();
            try (Stream<Path> files = Files.walk(app)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(index))
                        .forEach(path -> present.add(
                                app.relativize(path).toString().replace('\\', '/')));
            }
            assertThat(listed)
                    .as("%s must name exactly the files beside it — an unlisted file is on the"
                            + " classpath and served nowhere, a listed file that is missing"
                            + " fails the boot", REPO.relativize(index))
                    .isEqualTo(present);
        }
    }
}
