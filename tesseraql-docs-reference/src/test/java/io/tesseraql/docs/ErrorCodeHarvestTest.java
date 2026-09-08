package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The error index sees every site that raises a code, not just the one that declares it.
 *
 * <p>{@link ErrorIndex} resolved a raise site naming a constant only for the
 * {@code static final String NAME = "TQL-…"} idiom. A code held in a {@code TqlErrorCode}
 * constant — the compiler and runtime idiom — matched only at its own declaration, so every
 * {@code throw new TqlException(NAME, "…")} that named one was invisible and the code published
 * whatever meaning the declaration line happened to offer. Measured when this landed: <strong>96
 * codes across 366 throws</strong>, {@code TQL-LD-2810} raised at twenty sites,
 * {@code TQL-ROUTE-3100}'s eight distinct refusals all rendering as "unknown recipe".
 *
 * <p><strong>The invariant is structural, not a count.</strong> Asserting that
 * {@code TQL-ROUTE-3100} has exactly eight meanings would go red on a legitimate ninth refusal and
 * stay green on the thirty-six other constants with the same shape. What must hold is that a code
 * thrown with several <em>different</em> messages publishes more than one of them.
 *
 * <p>It asserts on messages rather than on provenance, and that distinction was found the hard
 * way: the first version checked that a file throwing a code appears among the code's sources,
 * and it was <strong>green against the unfixed index</strong>. The declaration's own
 * {@code new TqlErrorCode(TqlDomain.D, n)} is matched by the constructor scan, which already adds
 * the declaring file — and every raise site of a constant sits in that same file. Provenance was
 * never what got lost. The meanings were.
 */
class ErrorCodeHarvestTest {

    private static final Path REPO = Path.of("..");

    /** {@code static final TqlErrorCode NAME = new TqlErrorCode(TqlDomain.D, n)}. */
    private static final Pattern DECLARATION = Pattern.compile(
            "static\\s+final\\s+TqlErrorCode\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*"
                    + "new\\s+TqlErrorCode\\(\\s*TqlDomain\\.([A-Z]+)\\s*,\\s*(\\d+)\\s*\\)");

    /**
     * A raise site naming a constant declared in the same file, with the message it carries.
     *
     * <p>The first string literal after the code is enough to tell two refusals apart, which is
     * all this needs: the claim is that distinct messages reach the page, not that any particular
     * wording does.
     */
    private static final Pattern THROWN = Pattern.compile(
            "new\\s+TqlException\\(\\s*([A-Z][A-Z0-9_]*)\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyRaiseSiteReachesTheCodeItRaises() throws IOException {
        Map<String, Set<String>> messagesOf = new LinkedHashMap<>();
        int sites = 0;
        List<Path> sources = mainSources();
        for (Path file : sources) {
            String source = Files.readString(file);
            Map<String, String> local = new LinkedHashMap<>();
            Matcher declared = DECLARATION.matcher(source);
            while (declared.find()) {
                local.put(declared.group(1),
                        "TQL-" + declared.group(2) + "-" + declared.group(3));
            }
            if (local.isEmpty()) {
                continue;
            }
            Matcher thrown = THROWN.matcher(source);
            while (thrown.find()) {
                String code = local.get(thrown.group(1));
                if (code != null) {
                    messagesOf.computeIfAbsent(code, key -> new TreeSet<>())
                            .add(thrown.group(2));
                    sites++;
                }
            }
        }
        Map<String, Set<String>> manyMeanings = new LinkedHashMap<>();
        messagesOf.forEach((code, messages) -> {
            if (messages.size() >= 2) {
                manyMeanings.put(code, messages);
            }
        });

        // Non-vacuity on every input: a source list that came back short, a regex that stopped
        // matching either shape, or an index that scanned nothing each satisfy the assertion
        // below by having nothing to compare.
        assertThat(sources).as("the main source trees were walked").hasSizeGreaterThan(900);
        assertThat(sites).as("TqlErrorCode constants are still raised by name")
                .isGreaterThan(200);
        assertThat(manyMeanings).as("codes are still raised with several different messages")
                .hasSizeGreaterThan(50);

        Map<String, Map<Integer, ErrorIndex.Code>> index = ErrorIndex.scan(REPO);
        assertThat(index).as("the error index scanned the sources").isNotEmpty();

        List<String> flattened = new ArrayList<>();
        manyMeanings.forEach((code, messages) -> {
            Matcher parsed = Pattern.compile("TQL-([A-Z]+)-(\\d+)").matcher(code);
            if (!parsed.find()) {
                return;
            }
            ErrorIndex.Code entry = index.getOrDefault(parsed.group(1), Map.of())
                    .get(Integer.parseInt(parsed.group(2)));
            int published = entry == null ? 0 : entry.messages().size();
            if (published < 2) {
                flattened.add(code + " is thrown with " + messages.size()
                        + " different messages and publishes " + published);
            }
        });

        assertThat(flattened)
                .as("a code raised with several different messages publishes fewer than two of"
                        + " them, so the reference reports one refusal for many."
                        + " (Publishing a meaning is not a claim that it is well phrased.)")
                .isEmpty();
    }

    /** Every module's {@code src/main/java} tree, as {@link ErrorIndex} itself walks them. */
    private static List<Path> mainSources() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> modules = Files.list(REPO)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path tree = module.resolve("src/main/java");
                if (!Files.isDirectory(tree)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(tree)) {
                    files.addAll(walk.filter(p -> p.toString().endsWith(".java")).toList());
                }
            }
        }
        return files;
    }
}
