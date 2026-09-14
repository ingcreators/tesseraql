package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The bundled end-user surfaces carry no hard-coded text (docs/audit-medium-leads.md slice 8b,
 * F127): every visible string in the account, auth-ui and portal system apps is a
 * {@code #{tql.*}} catalog lookup, so the language a user picks on {@code /_tesseraql/account}
 * — "with zero configuration", as the page promises — reaches the account page, the inbox,
 * the task queue and the sign-in surfaces, and not only the framework chrome around them.
 * The class grew inside the audit window (#1167 added English pager labels beside bilingual
 * catalog keys); nothing linted a framework template for text, and this does.
 *
 * <p>What counts: a text run with two letters in a row outside an element whose body Thymeleaf
 * replaces ({@code th:text} / {@code th:utext}), a literal inside a {@code th:text="|…|"}
 * substitution, and a {@code placeholder}, {@code title}, {@code aria-label}, {@code alt} or
 * {@code data-hc-confirm*} (the confirm dialog's text)
 * written as a plain attribute. What does not: {@code <code>}, {@code <kbd>} and {@code <pre>}
 * bodies (identifiers, not prose), the {@code &middot;} sort of entity, and the Thymeleaf
 * prototype text an element with {@code th:text} carries.
 */
class BundledTemplateTextTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/resources/tesseraql/apps/account"),
            Path.of("src/main/resources/tesseraql/apps/auth-ui"),
            Path.of("src/main/resources/tesseraql/apps/portal"),
            // The operator consoles live in sibling modules (slice 8c).
            Path.of("../tesseraql-identity/src/main/resources/tesseraql/apps/iam-admin"),
            Path.of("../tesseraql-ops-ui/src/main/resources/tesseraql/apps/ops-console"));

    private static final Pattern WORD = Pattern.compile("[A-Za-z]{2,}");
    /** A tag, its attributes read quote-aware so a {@code >} inside an expression stays inside. */
    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z][a-zA-Z0-9:-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)(/?)>");
    private static final Pattern LITERAL_ATTRIBUTE = Pattern.compile(
            "\\s(placeholder|title|aria-label|alt|data-hc-confirm|data-hc-confirm-title|data-hc-confirm-label)=\"([^\"]*)\"");
    /** The Thymeleaf attributes whose value reaches the user as text. */
    private static final Pattern TEXT_EXPRESSION = Pattern.compile(
            "th:(u?text|replace|insert|title|placeholder|aria-label|alt)=\"([^\"]*)\"");
    /** A quoted or piped literal inside an expression, once the value expressions are gone. */
    private static final Pattern EXPRESSION_LITERAL = Pattern.compile("'([^']*)'|\\|([^|]*)\\|");
    private static final List<String> VERBATIM = List.of("code", "kbd", "pre", "script",
            "style", "svg");

    @Test
    void everyVisibleStringIsACatalogLookup() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".html")).sorted()
                        .toList()) {
                    scan(file, found);
                }
            }
        }
        assertThat(found).as("hard-coded text in the bundled end-user templates").isEmpty();
    }

    private static void scan(Path file, List<String> found) throws IOException {
        String html = Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "").replaceAll("(?i)<!DOCTYPE[^>]*>", "");
        String name = file.getFileName().toString();
        Deque<String> open = new ArrayDeque<>();
        int replacedDepth = 0;
        int verbatimDepth = 0;
        Matcher tag = TAG.matcher(html);
        int cursor = 0;
        while (tag.find()) {
            String text = html.substring(cursor, tag.start());
            if (replacedDepth == 0 && verbatimDepth == 0) {
                report(name, text, found, "text");
            }
            cursor = tag.end();
            boolean closing = !tag.group(1).isEmpty();
            String element = tag.group(2).toLowerCase(java.util.Locale.ROOT);
            String attributes = tag.group(3);
            boolean selfClosing = !tag.group(4).isEmpty() || VOID.contains(element);
            if (closing) {
                if (!open.isEmpty()) {
                    String popped = open.pop();
                    if (popped.endsWith("!")) {
                        replacedDepth--;
                    }
                    if (VERBATIM.contains(popped.replace("!", ""))) {
                        verbatimDepth--;
                    }
                }
                continue;
            }
            if (replacedDepth == 0 && verbatimDepth == 0) {
                Matcher expression = TEXT_EXPRESSION.matcher(attributes);
                while (expression.find()) {
                    // A ${…} or #{…} carries no text of its own; what is left in quotes or
                    // pipes is a literal the page would print as written.
                    String value = expression.group(2).replaceAll("[$#@]\\{[^}]*\\}", "");
                    Matcher literal = EXPRESSION_LITERAL.matcher(value);
                    while (literal.find()) {
                        String piece = literal.group(1) != null
                                ? literal.group(1)
                                : literal.group(2);
                        report(name, piece, found, "th:" + expression.group(1) + " literal");
                    }
                }
                Matcher literal = LITERAL_ATTRIBUTE.matcher(attributes);
                while (literal.find()) {
                    if (!attributes.contains("th:" + literal.group(1))
                            && !attributes.contains("th:attr=")) {
                        report(name, literal.group(2), found, literal.group(1) + " attribute");
                    }
                }
            }
            if (selfClosing) {
                continue;
            }
            boolean replaced = attributes.contains("th:text=") || attributes.contains("th:utext=");
            if (replaced) {
                replacedDepth++;
            }
            if (VERBATIM.contains(element)) {
                verbatimDepth++;
            }
            open.push(element + (replaced ? "!" : ""));
        }
    }

    private static final List<String> VOID = List.of("br", "hr", "img", "input", "link", "meta",
            "use", "path", "circle", "rect", "line", "polyline", "polygon");

    private static void report(String file, String text, List<String> found, String kind) {
        // An inline expression ([[${x}]]) is dynamic, not text; a dotted identifier with no
        // space in it (a placeholder such as `.approver`, a code such as `tql.app.use.*`) is
        // a name, not prose.
        String plain = text.replaceAll("\\[\\[.*?\\]\\]|\\[\\(.*?\\)\\]", " ")
                .replaceAll("&[a-z]+;|&#[0-9]+;", " ").strip();
        if (plain.matches("[A-Za-z0-9_.*-]*\\.[A-Za-z0-9_.*-]*")) {
            return;
        }
        if (WORD.matcher(plain).find()) {
            found.add(file + " (" + kind + "): " + plain.replaceAll("\\s+", " "));
        }
    }
}
