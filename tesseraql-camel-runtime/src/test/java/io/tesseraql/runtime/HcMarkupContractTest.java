package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The markup⇄kit drift guard (docs/studio-ux-refresh.md, slice 0): emitted hc markup is a
 * public contract of the framework (AGENTS.md rule 11), yet an invented class name renders
 * as nothing and fails silently — this codebase shipped {@code hc-disclosure},
 * {@code hc-empty__body}, {@code hc-field--grow} and invalid variant values for months
 * without a single test noticing. Every {@code hc-*} class token and every literal
 * {@code data-variant} value used by a template must exist in the WebJar's stylesheet, and
 * every {@code data-hc-confirm} must be wired so confirming actually fires (the kit only
 * re-emits {@code hc:confirmed}; a submit button rides the bootstrap's plain-form stand-in,
 * anything else needs {@code hx-trigger="hc:confirmed"} — see hc-briefs.md brief 4).
 *
 * <p>Scope: every {@code *.html} under the sibling modules' {@code src/main/resources}.
 * Markup emitted from Java string templates (the scaffolder) is covered by its own
 * snapshot tests, not scanned here.
 */
class HcMarkupContractTest {

    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();
    private static final String WEBJAR = "hypermedia-components__core";

    /** hc class tokens as they appear in class attributes. */
    private static final Pattern HC_TOKEN = Pattern.compile("hc-[A-Za-z0-9_-]+");
    /** A literal class attribute (static or the Thymeleaf append form). */
    private static final Pattern CLASS_ATTR = Pattern
            .compile("(?:class|th:classappend)=\"([^\"]*)\"");
    /** Class selectors in the kit stylesheet. */
    private static final Pattern CSS_CLASS = Pattern.compile("\\.(hc-[A-Za-z0-9_-]+)");
    /** A literal data-variant attribute. */
    private static final Pattern VARIANT_ATTR = Pattern.compile("data-variant=\"([a-z]+)\"");
    /** Quoted variant candidates inside a th:attr="data-variant=…" expression. */
    private static final Pattern VARIANT_EXPR = Pattern
            .compile("th:attr=\"[^\"]*data-variant=([^\"]*)\"");
    private static final Pattern QUOTED = Pattern.compile("'([a-z]+)'");
    /** data-hc-confirm* attribute names the kit's confirm behavior actually reads. */
    private static final Set<String> CONFIRM_ATTRS = Set.of("data-hc-confirm",
            "data-hc-confirm-title", "data-hc-confirm-label", "data-hc-confirm-variant",
            "data-hc-cancel-label");
    private static final Pattern CONFIRM_ATTR_NAME = Pattern
            .compile("(data-hc-(?:confirm|cancel)[A-Za-z-]*)=");
    private static final List<String> HX_VERBS = List.of("hx-get", "hx-post", "hx-put", "hx-patch",
            "hx-delete");

    @Test
    void everyHcClassInTemplatesExistsInTheKitStylesheet() throws Exception {
        Set<String> kit = kitClasses();
        List<String> violations = new ArrayList<>();
        for (Path template : templates()) {
            String html = Files.readString(template);
            Matcher attr = CLASS_ATTR.matcher(html);
            while (attr.find()) {
                Matcher token = HC_TOKEN.matcher(attr.group(1));
                while (token.find()) {
                    if (!kit.contains(token.group())) {
                        violations.add(rel(template) + ": class '" + token.group() + "'");
                    }
                }
            }
        }
        assertThat(violations)
                .as("hc-* classes with no selector in the kit stylesheet — invented markup"
                        + " renders as nothing (AGENTS.md rule 11)")
                .isEmpty();
    }

    @Test
    void everyLiteralVariantValueHasAKitSelector() throws Exception {
        String css = kitStylesheet();
        List<String> violations = new ArrayList<>();
        for (Path template : templates()) {
            for (String tag : tags(Files.readString(template))) {
                List<String> blocks = variantBlocks(tag, css);
                if (blocks.isEmpty()) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                Matcher literal = VARIANT_ATTR.matcher(tag);
                while (literal.find()) {
                    values.add(literal.group(1));
                }
                Matcher expr = VARIANT_EXPR.matcher(tag);
                while (expr.find()) {
                    // Strip ${…} first: a quoted string inside the condition
                    // (`${kind == 'job'} ? …`) is an operand, not a variant value.
                    Matcher quoted = QUOTED.matcher(expr.group(1).replaceAll("\\$\\{[^}]*}", ""));
                    while (quoted.find()) {
                        values.add(quoted.group(1));
                    }
                }
                for (String value : values) {
                    boolean known = blocks.stream().anyMatch(
                            block -> css
                                    .contains("." + block + "[data-variant=\"" + value + "\"]"));
                    if (!known) {
                        violations.add(rel(template) + ": " + blocks + " data-variant='" + value
                                + "' has no kit selector");
                    }
                }
            }
        }
        assertThat(violations)
                .as("literal data-variant values with no kit selector — an unknown variant"
                        + " silently renders as the default")
                .isEmpty();
    }

    @Test
    void everyConfirmedActionIsWiredToActuallyFire() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path template : templates()) {
            for (String tag : tags(Files.readString(template))) {
                if (!tag.contains("data-hc-confirm")) {
                    continue;
                }
                Matcher name = CONFIRM_ATTR_NAME.matcher(tag);
                while (name.find()) {
                    if (!CONFIRM_ATTRS.contains(name.group(1))) {
                        violations.add(rel(template) + ": unknown attribute '" + name.group(1)
                                + "' — the kit reads " + CONFIRM_ATTRS);
                    }
                }
                boolean plainSubmit = tag.contains("type=\"submit\"");
                boolean htmxWired = tag.contains("hc:confirmed")
                        && HX_VERBS.stream().anyMatch(verb -> tag.contains(verb + "="));
                if (!plainSubmit && !htmxWired) {
                    violations.add(rel(template) + ": data-hc-confirm on an element that can"
                            + " never fire — needs type=\"submit\" (plain-form stand-in) or an"
                            + " hx verb with hx-trigger=\"hc:confirmed\": "
                            + tag.replaceAll("\\s+", " "));
                }
            }
        }
        assertThat(violations)
                .as("confirm dialogs that confirm into nothing (hc-briefs.md brief 4)")
                .isEmpty();
    }

    /** Every template under the sibling modules' main resources. */
    private static List<Path> templates() throws IOException {
        List<Path> templates = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(REPO_ROOT, "tesseraql-*")) {
            for (Path module : modules) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(resources)) {
                    files.filter(p -> p.toString().endsWith(".html")).forEach(templates::add);
                }
            }
        }
        templates.sort(null);
        assertThat(templates).isNotEmpty();
        return templates;
    }

    /**
     * Splits markup into element tags with a quote-aware scan — a naive {@code <[^>]*>}
     * regex would stop at the {@code >} inside Thymeleaf expressions like
     * {@code th:attr="data-variant=${n > 0} ? …"}.
     */
    private static List<String> tags(String html) {
        List<String> tags = new ArrayList<>();
        int i = 0;
        int n = html.length();
        while (i < n) {
            if (html.charAt(i) != '<' || i + 1 >= n || !Character.isLetter(html.charAt(i + 1))) {
                i++;
                continue;
            }
            int j = i + 1;
            char quote = 0;
            while (j < n) {
                char c = html.charAt(j);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    break;
                }
                j++;
            }
            tags.add(html.substring(i, Math.min(j + 1, n)));
            i = j + 1;
        }
        return tags;
    }

    /** The tag's hc block classes that support data-variant in the kit stylesheet. */
    private static List<String> variantBlocks(String tag, String css) {
        List<String> blocks = new ArrayList<>();
        Matcher attr = CLASS_ATTR.matcher(tag);
        while (attr.find()) {
            Matcher token = HC_TOKEN.matcher(attr.group(1));
            while (token.find()) {
                String block = token.group();
                if (!block.contains("__") && css.contains("." + block + "[data-variant=")
                        && !blocks.contains(block)) {
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private static Set<String> kitClasses() throws IOException {
        Set<String> classes = new TreeSet<>();
        Matcher matcher = CSS_CLASS.matcher(kitStylesheet());
        while (matcher.find()) {
            classes.add(matcher.group(1));
        }
        assertThat(classes).isNotEmpty();
        return classes;
    }

    /** The full (unminified) kit stylesheet from the classpath WebJar. */
    private static String kitStylesheet() throws IOException {
        String version = new org.webjars.WebJarVersionLocator().version(WEBJAR);
        assertThat(version).as("hypermedia-components WebJar on the classpath").isNotNull();
        String resource = "META-INF/resources/webjars/" + WEBJAR + "/" + version + "/dist/hc.css";
        try (InputStream in = HcMarkupContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String rel(Path template) {
        return REPO_ROOT.relativize(template).toString().replace('\\', '/');
    }
}
