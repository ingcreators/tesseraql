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
 */
class ErrorCodeUniquenessTest {

    private static final Path REPO = Path.of("..");

    /** Codes deliberately declared at more than one site — one meaning each. */
    private static final Map<String, String> SHARED = Map.ofEntries(
            Map.entry("DECISION-4704", "miss policy disagrees with the rows (core compiles,"
                    + " yaml checks source defaults - the javadoc cross-references)"),
            Map.entry("WORKFLOW-3202", "a transition guard refused the request (single guard,"
                    + " or a dispatch where no member holds) - HTTP 422"),
            Map.entry("STUDIO-4040", "the studio object the request names does not exist"),
            Map.entry("SQL-4090", "a unique constraint violation, mapped per execution surface"),
            Map.entry("SQL-4002", "a check constraint violation, mapped per execution surface"),
            Map.entry("SQL-4093", "a serialization failure or deadlock; retryable"),
            Map.entry("FIELD-2001", "invalid input against the declared shape - HTTP 400"),
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
            Map.entry("YAML-1201", "a manifest path escapes the app home (traversal guard)"));

    private static final Pattern DECLARATION = Pattern.compile(
            "TqlErrorCode\\s+([A-Z_0-9]+)\\s*=\\s*new\\s+TqlErrorCode\\(\\s*TqlDomain\\.([A-Z]+)"
                    + "\\s*,\\s*(\\d+)\\s*\\)");

    @Test
    void everyErrorCodeIsDeclaredForOneRule() throws IOException {
        Map<String, Set<String>> byCode = new TreeMap<>();
        for (Path source : mainSources()) {
            String content = Files.readString(source);
            Matcher declaration = DECLARATION.matcher(content);
            while (declaration.find()) {
                String code = declaration.group(2) + "-" + declaration.group(3);
                byCode.computeIfAbsent(code, key -> new TreeSet<>())
                        .add(source.getFileName() + "#" + declaration.group(1));
            }
        }

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

        // The allowlist may only shrink: an entry whose code is no longer multi-declared is
        // stale and should be removed with the share it described.
        SHARED.keySet().forEach(code -> assertThat(byCode.getOrDefault(code, Set.of()))
                .as("SHARED entry '%s' no longer names a multi-declared code", code)
                .hasSizeGreaterThan(1));
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
