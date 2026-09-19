package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No page spells a view's primary source {@code source: sql} (docs/audit-low-leads.md slice 21,
 * unfiled 23). The reserved name is {@code main} ({@code RouteDefinition.MAIN}; the unified
 * source model, docs/unified-sources.md): {@code sql} was the pre-0.14 spelling, and a reader
 * who copied it from the declarative-views page got {@code TQL-VIEW-3308} from lint and build
 * alike — the docs taught what the framework refuses. A mention inside inline code, as in a
 * ledger that names the old spelling as history, is not a YAML example and is left alone.
 */
class ViewSourceSpellingLedgerTest {

    private static final Path DOCS = Path.of("..", "docs");

    /** A YAML-positioned {@code source: sql} — line start, a flow-map entry — never in backticks. */
    private static final Pattern RETIRED_SPELLING = Pattern
            .compile("(?:^|[\\s{,])source:\\s*sql\\b");

    @Test
    void noPageSpellsTheViewsPrimarySourceSql() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DOCS)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!file.toString().endsWith(".md")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (RETIRED_SPELLING.matcher(lines.get(i)).find()) {
                        offenders.add(DOCS.relativize(file) + ":" + (i + 1) + ": "
                                + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(offenders).as("docs lines spelling a view source `sql` (the name is `main`)")
                .isEmpty();
    }
}
