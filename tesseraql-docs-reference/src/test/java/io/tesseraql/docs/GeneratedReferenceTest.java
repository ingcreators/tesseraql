package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The drift guard from docs/docs-site.md (the {@code SchemaSyncTest} pattern): the
 * committed reference pages must match what the machine-readable sources generate —
 * change the schema or add an error code, and the build reminds you to refresh.
 */
class GeneratedReferenceTest {

    private static final Path REPO = Path.of("..");
    private static final String REFRESH = "the committed page drifted from its sources - refresh with:"
            + " mvn -q -pl tesseraql-docs-reference exec:java";

    @Test
    void committedYamlSurfaceMatchesGenerated() throws IOException {
        assertThat(Files.readString(REPO.resolve("docs/reference-yaml-surface.md")))
                .as(REFRESH)
                .isEqualTo(ReferenceGenerator.yamlSurface(REPO));
    }

    @Test
    void committedErrorCodesMatchGenerated() throws IOException {
        assertThat(Files.readString(REPO.resolve("docs/reference-error-codes.md")))
                .as(REFRESH)
                .isEqualTo(ReferenceGenerator.errorCodes(REPO));
    }

    @Test
    void scanFindsBothLiteralAndConstructorShapes() throws IOException {
        Map<String, Map<Integer, ErrorIndex.Code>> scanned = ErrorIndex.scan(REPO);

        // Lint codes are literals; runtime codes use the TqlErrorCode constructor -
        // both shapes must land, which is the whole point of the union scan.
        assertThat(scanned.get("YAML")).isNotEmpty();
        assertThat(scanned.get("WORKFLOW")).isNotEmpty();
        assertThat(scanned.values().stream().mapToInt(Map::size).sum()).isGreaterThan(250);
    }

    @Test
    void yamlSurfaceRendersTheDocumentContract() throws IOException {
        String surface = ReferenceGenerator.yamlSurface(REPO);

        assertThat(surface).contains("`kind` \\*");
        assertThat(surface).contains("enum: `route` \\| `job` \\| `view`");
        assertThat(surface).contains("## Shared definitions");
        assertThat(surface).contains("](#inputfield)");
    }

    @Test
    void errorIndexLinksProvenanceAndCookbookMentions() throws IOException {
        String index = ReferenceGenerator.errorCodes(REPO);

        assertThat(index).contains("## YAML");
        assertThat(index).contains("https://github.com/ingcreators/tesseraql/blob/main/");
        // At least one code is discussed in a cookbook page and links to it.
        assertThat(index).containsPattern("\\[[a-z-]+\\]\\([a-z-]+\\.md\\)");
    }
}
