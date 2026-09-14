package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every path {@code .dockerignore} excludes is a path this repository has, or one it
 * gitignores (docs/audit-medium-leads.md slice 10, F88).
 *
 * <p>The file excluded {@code editor/node_modules/} and {@code editor/*.vsix} — a directory
 * that never existed in this repository's history; the extension has been
 * {@code vscode-extension/} since it arrived — so every {@code docker build} from a developer's
 * checkout sent the extension's {@code node_modules}, its {@code .vsix} files and the in-tree
 * pnpm store, half a gigabyte, into the build context and invalidated the {@code COPY} layer
 * whenever pnpm touched its store. Nothing noticed because CI runners never have those
 * directories. A stale entry is silent; this makes it loud.
 *
 * <p>The rule is on the first path segment, which is where a rename shows: it names something
 * tracked (or the {@code .git} directory), or something {@code .gitignore} lists — a build
 * artefact that may not exist in this checkout is still a real path to exclude. Globs and
 * bare patterns ({@code *.log}, {@code **}{@code /target/}) are not path claims and are skipped.
 */
class DockerignoreLedgerTest {

    private static final Path REPO = Path.of("..");

    @Test
    void everyExcludedPathIsOneTheRepositoryHasOrIgnores() throws IOException {
        List<String> ignored = Files.readAllLines(REPO.resolve(".gitignore"),
                StandardCharsets.UTF_8);
        List<String> stale = new ArrayList<>();
        for (String line : Files.readAllLines(REPO.resolve(".dockerignore"),
                StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            String first = entry.split("/", 2)[0];
            if (first.isEmpty() || first.contains("*") || first.contains("?")) {
                continue;
            }
            boolean present = Files.exists(REPO.resolve(first));
            boolean gitignored = ignored.stream().map(String::strip)
                    .anyMatch(rule -> rule.equals(first) || rule.equals(first + "/")
                            || rule.equals("/" + first) || rule.equals("/" + first + "/"));
            if (!present && !gitignored) {
                stale.add(entry);
            }
        }
        assertThat(stale)
                .as(".dockerignore entries naming a path the repository neither has nor ignores")
                .isEmpty();
    }
}
