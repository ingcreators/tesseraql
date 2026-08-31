package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * One error code, one rule. Nothing used to stop two campaigns from allocating the same
 * {@code TQL-<DOMAIN>-<n>} to different problems, and three collisions shipped into the
 * published reference before this guard existed — {@code TQL-LD-2857} meant both "two group
 * keys name the same file" and "an export's enrichment failed", and the generated reference
 * merged the two descriptions into one row with no way to tell which rule fired.
 *
 * <p>A code <em>may</em> be declared in more than one class when the declarations are one rule
 * raised from more than one surface — a unique violation mapped by both the SQL producer and
 * the command processor is still one meaning. Each such share is listed here with its meaning,
 * so adding a new one is a reviewed decision instead of an accident.
 *
 * <p>The guard reads both declaration idioms: the {@code TqlErrorCode} constant the runtime
 * raises, and the {@code String} constant a lint rule family raises
 * (docs/lint-restructure.md decision 4). Lint codes used to be string literals at the raise
 * site, which no guard could see; the same code in two families is now a compile-time
 * duplicate declaration this test refuses.
 *
 * <p>A number held by both idioms is checked too, against its own list. A build-time lint and
 * the runtime error it anticipates share a number on purpose — {@code TQL-VIEW-3306} is an
 * unknown slot name whether the linter says so or the binder does — but that is a claim about
 * the two meanings, not something the idioms guarantee. {@code TQL-YAML-1103} meant both "a
 * declared locale has no catalog" and "an invalid {@code tesseraql.http.outbound}
 * declaration", and counting the idioms separately is exactly why nothing said so.
 */
class ErrorCodeUniquenessTest {

    private static final Path REPO = Path.of("..");

    /** Codes deliberately declared at more than one site — one meaning each. */
    private static final Map<String, String> SHARED = Map.ofEntries(
            Map.entry("ROUTE-3001", "the response could not be rendered - the body would not"
                    + " serialize, or a declared response header's value would not"),
            Map.entry("DECISION-4704", "miss policy disagrees with the rows (core compiles,"
                    + " yaml checks source defaults - the javadoc cross-references)"),
            Map.entry("WORKFLOW-3202", "a transition guard refused the request (single guard,"
                    + " or a dispatch where no member holds) - HTTP 422"),
            Map.entry("STUDIO-4040", "the studio object the request names does not exist"),
            Map.entry("SQL-4090", "a unique constraint violation, mapped per execution surface"),
            Map.entry("SQL-4002", "a check constraint violation, mapped per execution surface"),
            Map.entry("SQL-4093", "a serialization failure or deadlock; retryable"),
            Map.entry("FIELD-2001", "invalid input against the declared shape - HTTP 400"),
            Map.entry("FIELD-2002", "the mass-assignment guard refused a request field -"
                    + " unknown or non-writable - at the top level or inside an array element"),
            Map.entry("LD-2841", "an upload carried no content"),
            Map.entry("LD-2820", "an import request carried no file body"),
            Map.entry("SEC-4011", "authentication required or failed - HTTP 401"),
            Map.entry("APP-5203", "unsupported scaffold target (docs/scaffolding.md documents"
                    + " the umbrella: bad app name, non-empty target, keyless table)"),
            Map.entry("BATCH-5303", "a notification channel could not deliver, mail or webhook"),
            Map.entry("DECISION-4702", "a decision contract mismatch (core compiles, yaml"
                    + " checks source defaults - the DECISION-4704 pattern)"),
            Map.entry("LD-1", "a materializing read overflowed its row bound, whichever surface"
                    + " was reading"),
            Map.entry("LD-2821", "the file-transfer service is not configured"),
            Map.entry("LD-2822", "the named transfer does not exist"),
            Map.entry("LD-2840", "the attachment service is not configured"),
            Map.entry("RATE-4291", "an admission limit refused the request - HTTP 429"),
            Map.entry("SQL-4001", "a not-null constraint violation, mapped per surface"),
            Map.entry("SQL-4091", "a foreign-key violation, mapped per surface"),
            Map.entry("WORKFLOW-3203", "the task is not assigned to the caller - HTTP 403"),
            Map.entry("WORKFLOW-3210", "the workflow feature's backing store is not configured"),
            Map.entry("YAML-1201", "a manifest path escapes the app home (traversal guard)"),
            Map.entry("GOV-3001", "a route needing review has no valid approval, reported by"
                    + " the CLI command and the maven goal alike"));

    /**
     * Codes a lint rule and a runtime error both hold, because the lint says at build time what
     * the runtime would refuse — one meaning, reported twice as early as it can be.
     */
    private static final Map<String, String> ANTICIPATED = Map.ofEntries(
            Map.entry("BATCH-5304", "a mail channel is misdeclared, or its template escapes the"
                    + " app home"),
            Map.entry("FIELD-2003", "a validation rule declaration the build refuses"),
            Map.entry("FIELD-2004", "a declared block whose shape carries no usable work"
                    + " (a notify: block, a pipeline step)"),
            Map.entry("FIELD-4621", "a catalog's source declaration is contradictory or"
                    + " incomplete"),
            Map.entry("SEC-4132", "an invalid security.defaults declaration"),
            Map.entry("VIEW-3323", "a filters: entry names an input the route does not"
                    + " declare"),
            Map.entry("SEC-4135", "an invalid responseHeaders defaults declaration"),
            Map.entry("SQL-2101", "an expression that does not parse"),
            Map.entry("SQL-2111", "a file placeholder that cannot resolve where it is written"),
            Map.entry("VIEW-3302", "a view reference that does not resolve"),
            Map.entry("VIEW-3303", "a form action that names no usable POST route"),
            Map.entry("VIEW-3304", "a fields: entry the action route does not declare"),
            Map.entry("VIEW-3305", "an unknown widget name"),
            Map.entry("VIEW-3306", "an unknown slot name for the view kind"),
            Map.entry("VIEW-3308", "a children: entry naming a source the route does not"
                    + " declare"),
            Map.entry("VIEW-3317", "response.html.shell must be auto, always, or never"),
            Map.entry("VIEW-3318", "an embedded view that embeds further"),
            Map.entry("YAML-1007", "a message catalog file is malformed"),
            Map.entry("YAML-1102", "a notification channel that is not usable - invalid where"
                    + " it is declared, or named where it is not"),
            Map.entry("YAML-1409", "a route policy that resolves an atom from the request but"
                    + " cannot resolve on this route"));

    private static final Pattern DECLARATION = Pattern.compile(
            "TqlErrorCode\\s+([A-Z_0-9]+)\\s*=\\s*new\\s+TqlErrorCode\\(\\s*TqlDomain\\.([A-Z]+)"
                    + "\\s*,\\s*(\\d+)\\s*\\)");

    /** A lint family's code constant: {@code static final String NAME = "TQL-DOMAIN-n"}. */
    private static final Pattern LINT_DECLARATION = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"TQL-([A-Z]+)-(\\d+)\"");

    /** The rule families, whose codes must all be constants for this guard to see them. */
    private static final Path LINT_PACKAGE = REPO
            .resolve("tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint");

    private static final Pattern BARE_CODE = Pattern.compile("\"TQL-[A-Z]+-\\d+\"");

    @Test
    void everyErrorCodeIsDeclaredForOneRule() throws IOException {
        assertOneDeclarationPerCode(declarations(DECLARATION));
    }

    @Test
    void everyLintCodeIsDeclaredForOneRuleFamily() throws IOException {
        assertOneDeclarationPerCode(declarations(LINT_DECLARATION));
    }

    /**
     * A code a lint family spells as a literal is invisible to this guard, which is how
     * {@code TQL-FIELD-2004} came to answer four questions. The families keep their codes in
     * constants — their own, or {@code LintCodes} for the ones several families raise.
     */
    @Test
    void noRuleFamilyRaisesABareCodeLiteral() throws IOException {
        Map<String, Set<String>> literals = new TreeMap<>();
        try (Stream<Path> files = Files.walk(LINT_PACKAGE)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                String declared = LINT_DECLARATION.matcher(content).replaceAll("");
                Matcher bare = BARE_CODE.matcher(declared);
                while (bare.find()) {
                    literals.computeIfAbsent(bare.group(), key -> new TreeSet<>())
                            .add(source.getFileName().toString());
                }
            }
        }

        assertThat(literals)
                .as("lint codes spelled as literals at the raise site - declare the code as a"
                        + " constant on its rule family (or in LintCodes when a second family"
                        + " raises it) so the uniqueness guard can see it: %s", literals)
                .isEmpty();
    }

    /**
     * A number both idioms hold is one rule the lint reports early — or it is a collision that
     * reads as one rule in the published reference, where the two meanings are joined by a
     * {@code ·} with nothing to say which rule fired.
     */
    @Test
    void aNumberBothIdiomsHoldIsOneRuleReportedTwice() throws IOException {
        Map<String, Set<String>> runtime = declarations(DECLARATION);
        Map<String, Set<String>> lint = declarations(LINT_DECLARATION);

        Map<String, Set<String>> undeclared = new TreeMap<>();
        runtime.forEach((code, sites) -> {
            if (lint.containsKey(code) && !ANTICIPATED.containsKey(code)) {
                Set<String> both = new TreeSet<>(sites);
                both.addAll(lint.get(code));
                undeclared.put(code, both);
            }
        });

        assertThat(undeclared)
                .as("a lint rule and a runtime error hold the same number without a listed"
                        + " shared meaning - renumber the newer of the two, or add an"
                        + " ANTICIPATED entry saying the one meaning they report: %s", undeclared)
                .isEmpty();
    }

    /**
     * The allowlist may only shrink: an entry whose code is no longer multi-declared is stale
     * and should be removed with the share it described. Either idiom keeps an entry alive.
     */
    @Test
    void theSharedAllowlistOnlyNamesCodesThatAreStillShared() throws IOException {
        Map<String, Set<String>> runtime = declarations(DECLARATION);
        Map<String, Set<String>> lint = declarations(LINT_DECLARATION);

        SHARED.keySet().forEach(code -> assertThat(Math.max(
                runtime.getOrDefault(code, Set.of()).size(),
                lint.getOrDefault(code, Set.of()).size()))
                .as("SHARED entry '%s' no longer names a multi-declared code", code)
                .isGreaterThan(1));

        ANTICIPATED.keySet().forEach(code -> assertThat(
                runtime.containsKey(code) && lint.containsKey(code))
                .as("ANTICIPATED entry '%s' no longer names a code both idioms hold", code)
                .isTrue());
    }

    private static void assertOneDeclarationPerCode(Map<String, Set<String>> byCode) {
        Map<String, Set<String>> collisions = new TreeMap<>();
        byCode.forEach((code, sites) -> {
            if (sites.size() > 1 && !SHARED.containsKey(code)) {
                collisions.put(code, sites);
            }
        });

        assertThat(collisions)
                .as("error codes declared at more than one site without a listed shared"
                        + " meaning - renumber the newer rule (free numbers are cheap,"
                        + " pre-1.0 renumbering is free), or add a SHARED entry when the"
                        + " declarations really are one rule: %s", collisions)
                .isEmpty();
    }

    /** Every declaration the pattern finds, as code to {@code File#CONSTANT} sites. */
    private static Map<String, Set<String>> declarations(Pattern pattern) throws IOException {
        Map<String, Set<String>> byCode = new TreeMap<>();
        for (Path source : mainSources()) {
            Matcher declaration = pattern.matcher(Files.readString(source));
            while (declaration.find()) {
                String code = declaration.group(2) + "-" + declaration.group(3);
                byCode.computeIfAbsent(code, key -> new TreeSet<>())
                        .add(source.getFileName() + "#" + declaration.group(1));
            }
        }
        return byCode;
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> modules = Files.list(REPO)) {
            List<Path> roots = modules
                    .filter(path -> path.getFileName().toString().startsWith("tesseraql-"))
                    .map(path -> path.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .toList();
            List<Path> sources = new java.util.ArrayList<>();
            for (Path root : roots) {
                try (Stream<Path> files = Files.walk(root)) {
                    files.filter(path -> path.toString().endsWith(".java"))
                            .forEach(sources::add);
                }
            }
            return sources;
        }
    }
}
