package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The mapper-construction ledger (docs/duplication-consolidation.md, campaign 4, the
 * {@code SqlExecutorLedgerTest} pattern): every main source file constructing a Jackson
 * {@code ObjectMapper} is named here. The campaign found seventy-seven bare constructions —
 * several parsing untrusted request bodies with no declared {@code StreamReadConstraints},
 * while the YAML side had been hardened (docs/security-hardening.md) and the JSON side never
 * swept — and moved them onto the constrained factories; this ledger keeps them moved.
 *
 * <p><b>A new entry is refused by default.</b> A JSON mapper comes from
 * {@code io.tesseraql.yaml.JsonMappers.constrained()}; a YAML one from
 * {@code YamlMappers.constrained()}. What stays, and why:
 *
 * <ul>
 * <li>{@code JsonMappers}, {@code YamlMappers}, {@code SecurityJson}, {@code McpJson} — the
 * factories themselves; the latter two live below {@code tesseraql-yaml} and read the same
 * {@code JsonLimits} so the bounds cannot drift.</li>
 * <li>{@code FlagsSpec}, {@code MenuSpec} — write-only YAML emitters (no parse, nothing to
 * constrain) with their own writer features.</li>
 * <li>{@code ModuleBag}, {@code ModulesLock}, {@code JsonValues} — rebuild a factory's mapper
 * to add one feature (indented output; floats as {@code BigDecimal}); the factory's bounds and
 * pinned defaults carry over.</li>
 * </ul>
 *
 * <p><b>Every construction shape, qualified or not</b> (docs/jackson-3.md decision 5). The
 * pattern matched {@code new ObjectMapper(}, the builders and the YAML/XML siblings, but not a
 * fully qualified {@code new com.fasterxml.jackson.databind.ObjectMapper(} — and eight main
 * files built exactly that, outside the ledger, one of them parsing a Studio request body — nor
 * {@code new JsonMapper(}, the form Jackson 3's migration recipe writes. {@code rebuild()} is
 * matched too: a rebuilt mapper is a new configuration. {@code PackagedModules} and test-core's
 * {@code TestSuiteLoader} left the ledger in the same slice, onto the factories.
 */
class JsonMapperLedgerTest {

    /**
     * {@code new} with or without a package — {@code ObjectMapper}, {@code JsonMapper},
     * {@code YAMLMapper}, {@code XmlMapper} — their builders, and {@code .rebuild()}.
     */
    private static final java.util.regex.Pattern CONSTRUCTION = java.util.regex.Pattern.compile(
            "new\\s+(?:[a-z][a-z0-9_]*\\.)*(?:Object|Json|YAML|Xml)Mapper\\s*\\("
                    + "|(?:Json|YAML|Xml)Mapper\\.builder\\(|\\.rebuild\\(\\)");

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-cli/src/main/java/io/tesseraql/cli/modules/ModuleBag.java",
            "tesseraql-cli/src/main/java/io/tesseraql/cli/modules/ModulesLock.java",
            "tesseraql-mcp/src/main/java/io/tesseraql/mcp/McpJson.java",
            "tesseraql-security/src/main/java/io/tesseraql/security/SecurityJson.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/JsonMappers.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/YamlMappers.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/app/JsonValues.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/flags/FlagsSpec.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/menu/MenuSpec.java"));

    @Test
    void everyMapperConstructionIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on
                    // disk carries stale copies of exactly the files this ledger greps.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            // Every Jackson construction shape, not the literal one copy the
                            // campaign happened to sweep: the builder forms and the format
                            // siblings mint the same unconstrained mapper.
                            if (CONSTRUCTION.matcher(Files.readString(path)).find()) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files constructing an ObjectMapper — take one from"
                        + " io.tesseraql.yaml.JsonMappers/YamlMappers (or the sub-yaml"
                        + " factories) instead, or add the file here in review with a reason")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
