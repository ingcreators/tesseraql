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
 * <li>{@code PackagedModules}, {@code MysqlPlanInspector}, {@code PostgresPlanInspector} —
 * build- and test-time tools on trusted input (a jar this build produced, a database's own
 * EXPLAIN output).</li>
 * <li>The test-core pair ({@code RouteTestRunner}, {@code TestSuiteLoader}) — main-source
 * files that exist only to drive tests, the SQL ledger's precedent.</li>
 * </ul>
 */
class JsonMapperLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-apptasks/src/main/java/io/tesseraql/apptasks/PackagedModules.java",
            "tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/plan/MysqlPlanInspector.java",
            "tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/plan/PostgresPlanInspector.java",
            "tesseraql-mcp/src/main/java/io/tesseraql/mcp/McpJson.java",
            "tesseraql-security/src/main/java/io/tesseraql/security/SecurityJson.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/RouteTestRunner.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/TestSuiteLoader.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/JsonMappers.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/YamlMappers.java",
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
                            if (Files.readString(path).contains("new ObjectMapper(")) {
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
