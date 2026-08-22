package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A published page's parent-relative link escapes the site's root: the docs site serves
 * {@code docs/} alone, so {@code ](../anything)} renders as a link and 404s exactly where
 * readers actually read it. Repo artifacts — an example app, a deploy template, the
 * CHANGELOG — are linked by their absolute GitHub address instead, which works on the site
 * and on GitHub alike. Internal documents (the nav manifest's {@code EXCLUDED} list, the
 * same set {@link InternalDocsSyncTest} keeps honest) render only on GitHub, where a
 * parent-relative link works, so they stay free to use one.
 */
class PublishedLinkReachabilityTest {

    private static final Pattern EXCLUDED_ENTRY = Pattern.compile("'([A-Za-z0-9._-]+\\.md)'");
    /** An inline link or a reference definition whose target starts with {@code ../}. */
    private static final Pattern PARENT_LINK = Pattern.compile("\\]\\(\\.\\./|\\]:\\s*\\.\\./");

    @Test
    void publishedPagesLinkNothingAboveTheSiteRoot() throws IOException {
        List<String> excluded = navExclusions(Path.of("..", "docs-site", "nav.mjs"));
        assertThat(excluded)
                .as("nav.mjs EXCLUDED could not be parsed; this guard would pass vacuously")
                .isNotEmpty();

        List<String> escapes = new ArrayList<>();
        try (Stream<Path> docs = Files.list(Path.of("..", "docs"))) {
            for (Path doc : docs.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().toList()) {
                if (excluded.contains(doc.getFileName().toString())) {
                    continue;
                }
                List<String> lines = Files.readAllLines(doc);
                for (int i = 0; i < lines.size(); i++) {
                    if (PARENT_LINK.matcher(lines.get(i)).find()) {
                        escapes.add(
                                doc.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(escapes)
                .as("published docs link above the site root (404 on the site); link the "
                        + "absolute GitHub address instead, or exclude the page in nav.mjs")
                .isEmpty();
    }

    /** The {@code EXCLUDED} array's entries, read from the manifest as text. */
    private static List<String> navExclusions(Path manifest) throws IOException {
        String text = Files.readString(manifest);
        int start = text.indexOf("export const EXCLUDED");
        int end = text.indexOf("];", start);
        List<String> names = new ArrayList<>();
        Matcher matcher = EXCLUDED_ENTRY.matcher(text.substring(start, end));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
