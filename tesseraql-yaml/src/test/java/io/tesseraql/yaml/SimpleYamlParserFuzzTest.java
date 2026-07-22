package io.tesseraql.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fuzzes the YAML parse paths the runtime editor endpoints call (docs/security-hardening.md): every
 * input yields a value or a coded {@link TqlException} — never a raw {@code UncheckedIOException},
 * a SnakeYAML {@code YAMLException}, a Jackson {@code StreamConstraintsException}, or a
 * {@link StackOverflowError}. The regression cases pin the harmonized error contract and the
 * explicit nesting bound.
 */
class SimpleYamlParserFuzzTest {

    private static final SimpleYamlParser PARSER = new SimpleYamlParser();
    private static final String[] TOKENS = {
            ":", " ", "\n", "-", "  ", "[", "]", "{", "}", "&a ", "*a", "|", ">", "\"", "'", "#",
            "---", "...", "!!str ", "!!int ", "!!map ", "\t", "? ", ", ", "k", "v", "0", "version:",
            "id:", "kind:", "recipe:", "tesseraql/v1",
    };
    private static final String[] CORPUS = {
            "version: tesseraql/v1\nid: r\nkind: route\nrecipe: query-json\n",
            "server:\n  port: 8080\ndb:\n  main:\n    url: jdbc:postgresql://h/db\n",
            "a: &x [1, 2, 3]\nb: *x\n",
            "list:\n  - one\n  - two\n  - nested:\n      k: v\n",
            "\"quoted: value\": 1\n",
    };

    @Test
    @Timeout(30)
    void parseTreeFailsClosedOnAnyInput() {
        fuzz("parseTree", PARSER::parseTree, 20260722L, 4000);
    }

    @Test
    @Timeout(30)
    void parseRouteFailsClosedOnAnyInput() {
        fuzz("parseRoute", yaml -> PARSER.parseRoute(yaml, "<fuzz>"), 99L, 3000);
    }

    @Test
    void deeplyNestedDocumentIsCleanlyRejected() {
        String deep = "a: " + "[".repeat(500) + "1" + "]".repeat(500);
        assertThatThrownBy(() -> PARSER.parseTree(deep))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1001");
    }

    @Test
    void malformedFileYamlIsCodedNotUnchecked(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path file = dir.resolve("bad.yml");
        java.nio.file.Files.writeString(file, "version: tesseraql/v1\nid: [unclosed\n");
        // The file parse path used to surface a raw UncheckedIOException; it now codes cleanly.
        assertThatThrownBy(() -> PARSER.parseRoute(file))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1001");
    }

    @Test
    void validDocumentsStillParse() {
        assertThat(PARSER.parseTree("a:\n  b:\n    c: 1\n")).containsKey("a");
    }

    private static void fuzz(String label, Consumer<String> parse, long seed, int iterations) {
        Random rnd = new Random(seed);
        for (int i = 0; i < iterations; i++) {
            String input = generate(rnd);
            try {
                parse.accept(input);
            } catch (TqlException expected) {
                // the one allowed rejection
            } catch (Throwable thrown) {
                throw new AssertionError(label + " raised " + thrown.getClass().getName()
                        + " (expected only TqlException) on input:\n  ["
                        + input.length() + " chars] " + input.replace("\n", "\\n"), thrown);
            }
        }
    }

    private static String generate(Random rnd) {
        return switch (rnd.nextInt(3)) {
            case 0 -> {
                StringBuilder sb = new StringBuilder();
                int n = rnd.nextInt(80);
                for (int i = 0; i < n; i++) {
                    sb.append(TOKENS[rnd.nextInt(TOKENS.length)]);
                }
                yield sb.toString();
            }
            case 1 -> {
                StringBuilder sb = new StringBuilder(CORPUS[rnd.nextInt(CORPUS.length)]);
                int edits = 1 + rnd.nextInt(6);
                for (int e = 0; e < edits && sb.length() > 0; e++) {
                    int at = rnd.nextInt(sb.length());
                    switch (rnd.nextInt(3)) {
                        case 0 -> sb.deleteCharAt(at);
                        case 1 -> sb.insert(at, TOKENS[rnd.nextInt(TOKENS.length)]);
                        default -> sb.setCharAt(at, (char) (' ' + rnd.nextInt(95)));
                    }
                }
                yield sb.toString();
            }
            default -> TOKENS[rnd.nextInt(TOKENS.length)].repeat(1 + rnd.nextInt(300));
        };
    }
}
