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
            + " ./mvnw -q -pl tesseraql-docs-reference exec:java";

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
    void committedCliReferenceMatchesGenerated() throws IOException {
        assertThat(Files.readString(REPO.resolve("docs/reference-cli.md")))
                .as(REFRESH)
                .isEqualTo(ReferenceGenerator.cli());
    }

    @Test
    void committedConfigReferenceMatchesGenerated() throws IOException {
        assertThat(Files.readString(REPO.resolve("docs/reference-config.md")))
                .as(REFRESH)
                .isEqualTo(ReferenceGenerator.config(REPO));
    }

    /**
     * The CLI page is generated from the command model, so a subcommand cannot be documented
     * without existing — nor exist without being documented, which is what actually went wrong:
     * 27 subcommands were scattered through prose with no page listing them.
     */
    @Test
    void cliReferenceCoversEverySubcommandAndItsFlags() {
        String cli = ReferenceGenerator.cli();

        for (String command : new String[]{"dev", "deploy", "new", "scaffold", "lint", "test",
                "coverage", "migrate", "package", "admission", "modules", "job", "token",
                "identity-schema", "embedded-db", "duckdb", "mcp"}) {
            assertThat(cli).as("subcommand " + command).contains("## `" + command + "`");
        }
        // Nested subcommands render under their parent, not as top-level entries.
        assertThat(cli).contains("### `modules add`");
        // A param label carrying a pipe must not break out of its table cell.
        assertThat(cli).doesNotContain("<text|json>");
    }

    /**
     * The configuration index follows the error index's stance: a key appears because the code
     * reads it, and an undocumented key still appears rather than being quietly dropped.
     */
    @Test
    void configReferenceIndexesKeysWithProvenance() throws IOException {
        String config = ReferenceGenerator.config(REPO);

        assertThat(config).contains("## tesseraql.studio");
        assertThat(config).contains("`tesseraql.studio.confirmApply`");
        assertThat(config).contains("https://github.com/ingcreators/tesseraql/blob/main/");
        // The generator's own example keys are not framework configuration.
        assertThat(config).doesNotContain("`tesseraql.x.y`");
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

    /**
     * A code named in passing is not provenance (docs/audit-low-leads.md slice 16, DN-06b): a
     * comment explaining a neighbour, a Javadoc sentence, a printed hint. The provenance column
     * says "the raising files", and only the meaning half of the scan had ever applied the rule
     * — so the published reference listed {@code ErrorIndex.java} itself as a raise site of
     * {@code TQL-LD-2810}, 110 links in all, and hid seven real raise sites behind "+N more".
     * A bare literal handed to a lint reporter stays a raise site: that literal IS the code.
     */
    @Test
    void aCodeNamedInACommentOrAPrintedHintIsNotProvenance(
            @org.junit.jupiter.api.io.TempDir Path repo) throws IOException {
        Path tree = Files.createDirectories(repo.resolve("m/src/main/java"));
        Files.writeString(tree.resolve("Raiser.java"), """
                class Raiser {
                    static final TqlErrorCode X = new TqlErrorCode(TqlDomain.LD, 2810);
                }
                """);
        Files.writeString(tree.resolve("Lint.java"), """
                class Lint {
                    void lint() {
                        report("TQL-LD-2810", "the transfer service failed");
                    }
                }
                """);
        Files.writeString(tree.resolve("Neighbour.java"), """
                /** {@code TQL-LD-2810} is raised at twenty sites; this class raises none. */
                class Neighbour {
                    void hint() {
                        // the transfer service answers TQL-LD-2810 for this
                        System.err.println("see TQL-LD-2810 for the transfer's own reason");
                    }
                }
                """);

        Map<String, Map<Integer, ErrorIndex.Code>> scanned = ErrorIndex.scan(repo);

        assertThat(scanned.get("LD").get(2810).sources())
                .as("the raising files, and no file that only mentions the code")
                .containsExactly("m/src/main/java/Lint.java", "m/src/main/java/Raiser.java");
    }

    @Test
    void yamlSurfaceRendersTheDocumentContract() throws IOException {
        String surface = ReferenceGenerator.yamlSurface(REPO);

        assertThat(surface).contains("`kind` \\*");
        // One section per document kind, since one schema per kind (docs/unified-sources.md).
        assertThat(surface).contains("## Route documents", "## Job documents",
                "## View documents");
        assertThat(surface).contains("## Shared definitions");
        // A value shape shared across kinds is described once and linked to from each.
        assertThat(surface).contains("](#inputfield)");
    }

    /**
     * A key several kinds declare identically is stored once, in the shared definitions, and
     * inlined into each kind's table — a reader of the route section should not have to follow
     * a link to learn what `version:` is, and a raw `$ref` reaching the page would mean the
     * renderer stopped resolving across files.
     */
    @Test
    void yamlSurfaceInlinesSharedPropertyDefinitions() throws IOException {
        String surface = ReferenceGenerator.yamlSurface(REPO);

        assertThat(surface).contains("| `version` \\* | const `tesseraql/v1` |");
        assertThat(surface).doesNotContain("tesseraql-defs-v1.schema.json");
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
