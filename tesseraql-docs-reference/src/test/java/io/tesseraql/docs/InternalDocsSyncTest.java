package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code ErrorIndex.INTERNAL_DOCS} and the docs-site nav manifest's {@code EXCLUDED} list are
 * the same set expressed twice: the documents that are internal planning, not user
 * documentation. Nothing checked that they agreed, and they drifted — a document excluded from
 * the site was still eligible to be cited as an error code's cookbook page, sending a reader to
 * a design document that is not published.
 *
 * <p>Two lists, one truth: this fails the build when either side gains an entry the other lacks.
 */
class InternalDocsSyncTest {

    private static final Pattern ENTRY = Pattern.compile("'([A-Za-z0-9._-]+\\.md)'");

    @Test
    void internalDocsMatchesTheNavManifestExclusions() throws IOException {
        List<String> excluded = navExclusions(Path.of("..", "docs-site", "nav.mjs"));

        assertThat(excluded)
                .as("nav.mjs EXCLUDED could not be parsed; the sync guard would pass vacuously")
                .isNotEmpty();

        List<String> missingFromIndex = excluded.stream()
                .filter(name -> !ErrorIndex.isInternalDoc(name))
                .toList();
        assertThat(missingFromIndex)
                .as("excluded from the site but still citable by the error index: add to "
                        + "ErrorIndex.INTERNAL_DOCS")
                .isEmpty();

        List<String> missingFromNav = ErrorIndex.internalDocs().stream()
                .filter(name -> !excluded.contains(name))
                .toList();
        assertThat(missingFromNav)
                .as("treated as internal by the error index but published by nav.mjs: remove "
                        + "from ErrorIndex.INTERNAL_DOCS or exclude it from the site")
                .isEmpty();
    }

    /** The {@code EXCLUDED} array's entries, read from the manifest as text. */
    private static List<String> navExclusions(Path manifest) throws IOException {
        String text = Files.readString(manifest);
        int start = text.indexOf("export const EXCLUDED");
        int end = text.indexOf("];", start);
        List<String> names = new ArrayList<>();
        Matcher matcher = ENTRY.matcher(text.substring(start, end));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
