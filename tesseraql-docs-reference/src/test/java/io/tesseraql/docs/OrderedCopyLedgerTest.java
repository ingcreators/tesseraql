package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The ordered-copy ledger (docs/deterministic-output.md, decision 2 — the campaign's real
 * deliverable, in the {@code SqlExecutorLedgerTest} pattern). {@code Map.of} and {@code Map.copyOf}
 * iterate in an order derived from a per-JVM salt, so a map built with one of them and later
 * iterated makes the output it reaches differ between two builds of identical source. The campaign
 * found that reaching a rendered form, a validation error, an MCP tool's advertised contract, an
 * outbound URL, a webhook body, a rewritten {@code config/flags.yml}, the linter's own JSON, and
 * both Ed25519-signed release documents.
 *
 * <p><b>A new entry is refused by default.</b> A map or set built from what an author declared
 * goes through {@code io.tesseraql.core.util.OrderedCopies}; a document assembled for output is
 * built with a {@code LinkedHashMap}. This ledger is the census over the declaration layer — the
 * packages that hold or emit an author's own words — and it shrinks, never grows.
 *
 * <p>Three things the scanner does deliberately, each of which cost a slice to learn:
 *
 * <ul>
 * <li><b>It lexes comments and string literals out first.</b> Several sites carry a comment saying
 * they deliberately avoid {@code copyOf}; a whole-file matcher would count the comment, so
 * deleting one would move the census and writing the correct explanatory comment would turn the
 * build red.</li>
 * <li><b>It counts arguments.</b> {@code Map.of()} and {@code Map.of(k, v)} compile to
 * {@code ImmutableCollections}' zero- and one-entry maps, which have no table and cannot vary, so
 * matching them would bury the real sites under noise. Only two entries or more are salt-capable.
 * </li>
 * <li><b>It scopes to the declaration layer.</b> Tree-wide, 152 files build a salt-capable map or
 * set, almost all of them constants read by key. The defect is specific to a map that carries what
 * an author wrote, and those live here.</li>
 * </ul>
 *
 * <p>What stays, and why — every entry is a constant vocabulary or an allow-list, tested for
 * membership and never iterated into output:
 *
 * <ul>
 * <li>{@code ViewSpec} — the strict per-level key vocabularies (TQL-VIEW-3314) and the envelope
 * keys, all {@code contains} checks.</li>
 * <li>{@code InputField} — the declared type and widget vocabularies.</li>
 * <li>{@code MessagingChannels} — the built-in transport names.</li>
 * <li>{@code RowContract} — a column's {@code codes} allow-list, read only as
 * {@code codes().contains(text)}.</li>
 * <li>{@code MailNotifier} — a fixed two-entry lookup, read by key.</li>
 * </ul>
 */
class OrderedCopyLedgerTest {

    private static final Path REPO = Path.of("..");

    /**
     * The declaration layer: packages whose types hold, or directly emit, what an application
     * author wrote. A salted map here reaches output; elsewhere it is almost always a constant.
     */
    private static final List<String> SCOPE = List.of(
            "/io/tesseraql/yaml/model/",
            "/io/tesseraql/yaml/config/",
            "/io/tesseraql/yaml/view/",
            "/io/tesseraql/yaml/flags/",
            "/io/tesseraql/yaml/notify/",
            "/io/tesseraql/yaml/messaging/",
            "/io/tesseraql/yaml/sbom/",
            "/io/tesseraql/yaml/release/",
            "/io/tesseraql/core/files/");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-core/src/main/java/io/tesseraql/core/files/RowContract.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/messaging/MessagingChannels.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/model/InputField.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/notify/MailNotifier.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/view/ViewSpec.java"));

    @Test
    void everySaltCapableConstructionInTheDeclarationLayerIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on disk
                    // carries stale copies of exactly the files this ledger scans.
                    .filter(path -> !path.toString().contains("/."))
                    .filter(path -> SCOPE.stream()
                            .anyMatch(pkg -> path.toString().replace('\\', '/').contains(pkg)))
                    .forEach(path -> {
                        try {
                            if (!saltCapable(lex(Files.readString(path))).isEmpty()) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("declaration-layer files building a salt-capable map or set — take"
                        + " io.tesseraql.core.util.OrderedCopies, or build a LinkedHashMap, or add"
                        + " the file here in review with the reason its order cannot reach output")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }

    /** The scanner is sensitive: the pattern it looks for is the one the campaign converted. */
    @Test
    void theScannerSeesASaltCapableConstruction() {
        assertThat(saltCapable(lex("var m = Map.of(\"a\", 1, \"b\", 2);")))
                .containsExactly("Map.of");
        assertThat(saltCapable(lex("var m = Map.copyOf(declared);"))).containsExactly("Map.copyOf");
        assertThat(saltCapable(lex("var s = Set.of(\"a\", \"b\");"))).containsExactly("Set.of");

        // One- and zero-entry factories have no table and cannot vary.
        assertThat(saltCapable(lex("var m = Map.of();"))).isEmpty();
        assertThat(saltCapable(lex("var m = Map.of(\"only\", 1);"))).isEmpty();
        assertThat(saltCapable(lex("var s = Set.of(\"only\");"))).isEmpty();

        // A nested call is one argument, not two.
        assertThat(saltCapable(lex("var m = Map.of(\"k\", Map.of(\"a\", 1, \"b\", 2));")))
                .containsExactly("Map.of");

        // Comments and string literals are not code.
        assertThat(saltCapable(lex("// Not Map.copyOf: the order feeds the message.\nint x;")))
                .isEmpty();
        assertThat(saltCapable(lex("/* Map.of(\"a\", 1, \"b\", 2) */ int x;"))).isEmpty();
        assertThat(saltCapable(lex("String s = \"Map.of(a, 1, b, 2)\";"))).isEmpty();
    }

    /** Every salt-capable construction in already-lexed source, named by its factory. */
    private static List<String> saltCapable(String code) {
        List<String> found = new ArrayList<>();
        for (String factory : List.of("Map.copyOf", "Set.copyOf")) {
            int from = 0;
            while ((from = indexOfCall(code, factory, from)) >= 0) {
                found.add(factory);
                from += factory.length();
            }
        }
        // Map.of takes two arguments per entry; Set.of takes one per element.
        for (String factory : List.of("Map.of", "Set.of")) {
            int minimumArguments = factory.startsWith("Map") ? 4 : 2;
            int from = 0;
            while ((from = indexOfCall(code, factory, from)) >= 0) {
                if (arguments(code,
                        code.indexOf('(', from + factory.length())) >= minimumArguments) {
                    found.add(factory);
                }
                from += factory.length();
            }
        }
        return found;
    }

    /**
     * The next {@code factory(} call. A qualified {@code java.util.Map.of(} counts — several model
     * records spell it that way — while a same-named method on another type does not.
     */
    private static int indexOfCall(String code, String factory, int from) {
        int at = from;
        while ((at = code.indexOf(factory + "(", at)) >= 0) {
            int start = at >= 10 && code.startsWith("java.util." + factory + "(", at - 10)
                    ? at - 10
                    : at;
            char before = start == 0 ? ' ' : code.charAt(start - 1);
            if (!Character.isJavaIdentifierPart(before) && before != '.') {
                return at;
            }
            at += factory.length();
        }
        return -1;
    }

    /** Top-level comma-separated arguments of the call whose open parenthesis is at {@code open}. */
    private static int arguments(String code, int open) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = open + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return commas + (any ? 1 : 0);
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            } else if (!Character.isWhitespace(c)) {
                any = true;
            }
        }
        return commas + (any ? 1 : 0);
    }

    /** Source with comments and string/char literals blanked, so only code is matched. */
    private static String lex(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? source.length() : end + 3;
                // A literal becomes one token, never nothing: an argument counter that saw a
                // blanked literal would read Set.of("a", "b") as a single element.
                code.append('_');
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                i++;
                while (i < source.length() && source.charAt(i) != quote) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                code.append('_');
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else {
                code.append(source.charAt(i));
                i++;
            }
        }
        return code.toString();
    }
}
