package io.tesseraql.runtime;

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
 * The framework's own markup addresses the application it is served by, not the origin
 * (docs/base-path.md slice 3).
 *
 * <p>A root-absolute {@code href="/x"} in a bundled app resolves against the origin, so under a
 * base path it names an address nothing answers at — which is the defect the design opened with.
 * The remedy is a link expression, {@code th:href="@{/x}"}, and one overridden method resolves
 * every one of them. This guard is what keeps the next hand-written page from quietly reopening
 * the hole: the sweep of two hundred and sixty URLs is only worth doing once.
 *
 * <p>Scoped to the templates the framework ships. An application's own templates are the
 * author's, and are lint-warned rather than failed (docs/base-path.md decision 3).
 */
class BundledAppUrlsTest {

    private static final String URL_ATTRIBUTE_NAMES = "href|src|action|formaction|hx-get|hx-post|hx-put|hx-patch|hx-delete"
            + "|sse-connect|data-value";

    /** A plain attribute whose value is a root-absolute URL. */
    private static final Pattern URL_ATTRIBUTE = Pattern.compile(
            "(?<![\\w:-])(" + URL_ATTRIBUTE_NAMES + ")=\"(/[^\"]*)\"");

    /**
     * The same thing written as a Thymeleaf literal substitution — {@code th:href="|/x/${id}|"}.
     * It renders a root-absolute URL just as surely, and reads like it is already dynamic, which
     * is exactly how forty-seven of them survived the first pass over the bundled apps.
     */
    private static final Pattern UNLINKED_SUBSTITUTION = Pattern.compile(
            "(?:th:(?:" + URL_ATTRIBUTE_NAMES + ")=\"|(?:" + URL_ATTRIBUTE_NAMES + ")=)\\|/");

    /** One start tag. Attribute values may hold {@code <} or {@code >} (a th:if comparison). */
    private static final Pattern TAG = Pattern.compile(
            "<[a-zA-Z][a-zA-Z0-9:.-]*"
                    + "(?:\\s+[^\\s=<>/]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'<>]+))?)*"
                    + "\\s*/?>",
            Pattern.DOTALL);

    /** Where the framework's shipped markup lives: the shared patterns and the bundled apps. */
    private static final List<String> ROOTS = List.of(
            "../tesseraql-compiler/src/main/resources/tesseraql/templates",
            "../tesseraql-studio/src/main/resources/tesseraql/apps",
            "../tesseraql-ops-ui/src/main/resources/tesseraql/apps",
            "../tesseraql-identity/src/main/resources/tesseraql/apps",
            "../tesseraql-runtime/src/main/resources/tesseraql/apps");

    @Test
    void noShippedTemplateEmitsARootAbsoluteUrl() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                    String text = Files.readString(file);
                    Matcher substitutions = UNLINKED_SUBSTITUTION.matcher(text);
                    while (substitutions.find()) {
                        offenders.add(file + ": " + substitutions.group());
                    }
                    Matcher tags = TAG.matcher(text);
                    while (tags.find()) {
                        String tag = tags.group();
                        Matcher urls = URL_ATTRIBUTE.matcher(tag);
                        while (urls.find()) {
                            // A static value beside its th: twin on the same element is a
                            // natural-template prototype Thymeleaf replaces at render, so it
                            // never reaches a browser.
                            if (!tag.contains("th:" + urls.group(1) + "=")) {
                                offenders.add(file + ": " + urls.group());
                            }
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("write @{/x} so the URL resolves against the application's base path")
                .isEmpty();
    }
}
