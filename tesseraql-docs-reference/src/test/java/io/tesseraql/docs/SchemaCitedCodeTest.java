package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every error code a shipped schema cites is one the framework can actually raise.
 *
 * <p>Two did not. {@code TQL-CAMEL-3101} and {@code TQL-CAMEL-3114} sat in the shared definitions'
 * descriptions from #754 and survived the #957 domain rename, which made every {@code TQL-CAMEL-*}
 * code a {@code TQL-ROUTE-*} one. They were not merely stale comments: schema descriptions are
 * harvested verbatim into {@code docs/reference-yaml-surface.md}, so five rows of a published page
 * told a reader to look up a code that does not exist and never would.
 *
 * <p>The check lives here rather than beside the schemas because it needs both halves at once —
 * the schema resources ship in {@code tesseraql-yaml}, which this module reaches transitively, and
 * {@link ErrorIndex} is the only thing that knows which codes are real.
 *
 * <p>It reads the schemas as <strong>raw text</strong>, not as a walk over description fields: a
 * code that moves into a {@code $comment}, a {@code title} or an enum value is cited just as
 * publicly, and a structural walk would stop seeing it the moment it moved.
 */
class SchemaCitedCodeTest {

    private static final Path REPO = Path.of("..");

    private static final Path SCHEMAS = REPO.resolve("tesseraql-yaml/src/main/resources/schema");

    /** The trailing guard keeps a wildcard mention like {@code TQL-ADM-47xx} out of the scan. */
    private static final Pattern CITED = Pattern.compile("TQL-([A-Z]+)-(\\d+)(?![0-9A-Za-z])");

    @Test
    void everyCodeCitedByASchemaExists() throws IOException {
        List<String> cited = new ArrayList<>();
        List<String> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(SCHEMAS)) {
            for (Path schema : entries.filter(p -> p.toString().endsWith(".schema.json"))
                    .sorted().toList()) {
                files.add(schema.getFileName().toString());
                Matcher matcher = CITED.matcher(Files.readString(schema));
                while (matcher.find()) {
                    cited.add(matcher.group(0) + "  (" + schema.getFileName() + ")");
                }
            }
        }

        Map<String, Map<Integer, ErrorIndex.Code>> index = ErrorIndex.scan(REPO);

        // Non-vacuity on both sides. A regex that stopped matching, a directory that moved, or an
        // index that came back empty each satisfy the assertion below by having nothing to check
        // — which is how a guard like this goes green while the citations rot.
        assertThat(files).as("the schema directory lists its files").hasSizeGreaterThan(10);
        assertThat(cited).as("the schemas still cite error codes").hasSizeGreaterThan(25);
        assertThat(index).as("the error index scanned the sources").isNotEmpty();

        List<String> dead = cited.stream().filter(citation -> {
            Matcher matcher = CITED.matcher(citation);
            if (!matcher.find()) {
                return false;
            }
            Map<Integer, ErrorIndex.Code> domain = index.get(matcher.group(1));
            return domain == null || !domain.containsKey(Integer.parseInt(matcher.group(2)));
        }).toList();

        assertThat(dead)
                .as("a schema cites an error code the framework cannot raise. Schema descriptions"
                        + " are harvested into docs/reference-yaml-surface.md, so this ships to a"
                        + " published page. (That the code exists is not a claim that it is the"
                        + " one this rule raises.)")
                .isEmpty();
    }
}
