package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The identifier contract has one home (docs/two-way-sql-parser.md decision 13).
 *
 * <p>The finding this refuses was filed as one regex. The damage was the copies. Two lint families
 * and the framework's own browser scanner had each written out {@code [\p{L}_][\p{L}\p{N}_]*} by
 * hand, so widening the authority class left the <em>write-scope security lint</em> blind to
 * exactly the names the contract newly admitted — a guard that stops firing rather than one that
 * fires wrongly, which is the failure mode docs/unicode-identifiers.md exists to abolish.
 *
 * <p><b>What this cannot see.</b> It matches the class as it is spelled today. A copy written with
 * the character classes in a different order, or built from a variable, passes here. It is a guard
 * against the careless re-inline, not against a determined one — the real defense is that
 * {@code SqlIdentifiers} is the obvious place to look.
 */
class IdentifierContractLedgerTest {

    private static final Path REPO = Path.of("..");

    /** The contract's own shape, in the spellings a re-inline would use. */
    private static final Pattern INLINED = Pattern.compile(
            "\\[\\\\*p\\{L\\}(\\\\*p\\{Mn\\}\\\\*p\\{Mc\\})?\\\\*p\\{N\\}_\\]"
                    + "|\\[A-Za-z_\\]\\[A-Za-z0-9_\\]");

    /**
     * Every place the class may be written out, with the reason it is not composed.
     *
     * <ul>
     * <li>{@code SqlIdentifiers} — the contract itself.</li>
     * <li>{@code ScopeRules} — the write-scope patterns match SQL <em>text</em> and carry the dot
     * inside the class. Composing a dotted constant picks the wrong segment out of
     * {@code cat.sch.orders}, so the lint would key on a schema (decision 14).</li>
     * <li>{@code tesseraql.js} — ships to the browser and cannot read a Java constant. Kept in
     * step by hand, and the only copy of the SQL contract this ledger allows.</li>
     * <li>{@code ErrorIndex} — a scan for a <em>Java</em> identifier, the name of a code constant
     * in this repository's own sources. A different grammar that happens to be spelled the same,
     * and one that must not widen: a Java field name is ASCII here by convention.</li>
     * </ul>
     *
     * <p>Deliberately absent, because they are different grammars and are not spelled like this:
     * {@code Sql2WayParser}'s file-name charsets and {@code StepContext}'s dotted-path template
     * placeholder.
     *
     * <p>The ASCII spelling is matched too, and on purpose: {@code [A-Za-z_][A-Za-z0-9_]*} is what
     * every one of these call sites carried before the Unicode campaign, so it is the shape a
     * re-inline is most likely to take.
     */
    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-core/src/main/java/io/tesseraql/core/sql/SqlIdentifiers.java",
            "tesseraql-docs-reference/src/main/java/io/tesseraql/docs/ErrorIndex.java",
            "tesseraql-runtime/src/main/resources/tesseraql/assets/tesseraql.js",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint/ScopeRules.java"));

    @Test
    void theIdentifierClassIsWrittenOutOnlyWhereItMustBe() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(IdentifierContractLedgerTest::isShippedSource)
                    .forEach(path -> {
                        try {
                            if (INLINED.matcher(Files.readString(path)).find()) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }

        assertThat(found)
                .as("files spelling out the identifier character class. Compose"
                        + " SqlIdentifiers.IDENTIFIER or SqlIdentifiers.PART instead: a copy is how"
                        + " the write-scope security lint went blind to the names the contract"
                        + " admits. If a call site genuinely cannot compose it, add it here with"
                        + " the reason")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }

    /** Main sources the build ships: Java and browser assets, never tests, targets or worktrees. */
    private static boolean isShippedSource(Path path) {
        String name = path.toString().replace('\\', '/');
        if (name.contains("/target/") || name.contains("/.")) {
            return false;
        }
        return name.contains("/src/main/java/") && name.endsWith(".java")
                || name.contains("/src/main/resources/") && name.endsWith(".js");
    }
}
