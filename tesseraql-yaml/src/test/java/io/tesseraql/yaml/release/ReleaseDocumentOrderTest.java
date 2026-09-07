package io.tesseraql.yaml.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.sbom.SbomGenerator;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The release documents reproduce: two builds of identical source emit identical bytes.
 *
 * <p><b>Why this is a structural walk and not a byte comparison.</b> The obvious guard —
 * generate twice and compare — cannot work. Iteration order is fixed for the lifetime of a JVM,
 * so two calls in one process always agree, which is why {@code evidenceIsDeterministic} passed
 * for years over a document that did not reproduce. Comparing across two forked JVMs is no better:
 * the evidence document's only salted map had two keys, so two fresh JVMs agreed half the time and
 * the guard would have been green on every other run. Reaching a negligible flake budget that way
 * needs upwards of twenty forks.
 *
 * <p>So this walks the built document trees instead and fails on the <em>cause</em>. A
 * {@code Map.of}/{@code Map.copyOf} of two or more entries is an
 * {@code ImmutableCollections$MapN}, and which class the factory returns is decided by the call
 * site's arity, never by the salt — so the assertion is red on every pre-fix boot and green on
 * every post-fix boot, in one process, with no forking. One- and zero-entry maps are
 * {@code Map1}/the empty map: they have no table, cannot vary, and are allowed.
 *
 * <p>It is deliberately a deny-list. An allow-list naming {@code LinkedHashMap} would go red the
 * moment a site moved to {@code OrderedCopies}, which returns an unmodifiable view rather than a
 * {@code LinkedHashMap}.
 */
class ReleaseDocumentOrderTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    private static final List<SbomGenerator.MavenComponent> DEPENDENCIES = List.of(
            new SbomGenerator.MavenComponent("org.postgresql", "postgresql", "42.7.4",
                    "ab12".repeat(16), List.of("BSD-2-Clause")),
            new SbomGenerator.MavenComponent("io.vertx", "vertx-core", "4.18.0", "cd34".repeat(16),
                    List.of("Apache-2.0", "EPL-2.0")));

    private static List<String> keys(Map<?, ?> map) {
        return map.keySet().stream().map(String::valueOf).toList();
    }

    /** Every node whose iteration order the salt would choose, deepest path first. */
    private static List<String> saltedNodes(Object node, String path) {
        List<String> found = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            if (map.size() >= 2
                    && map.getClass().getName().startsWith("java.util.ImmutableCollections$Map")) {
                found.add(path + " (" + map.size() + " entries: " + map.keySet() + ")");
            }
            map.forEach((key, value) -> found.addAll(saltedNodes(value, path + "." + key)));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                found.addAll(saltedNodes(list.get(i), path + "[" + i + "]"));
            }
        }
        return found;
    }

    @Test
    void theEvidenceDocumentCarriesNoSaltedMap() {
        Map<String, Object> evidence = new ReleaseEvidence().build(exampleApp(),
                "com.example.user-admin", "1.0.0");

        assertThat(saltedNodes(evidence, "evidence")).isEmpty();
    }

    @Test
    void theSbomCarriesNoSaltedMap() {
        Map<String, Object> bom = new SbomGenerator()
                .cycloneDx(exampleApp(), "user-admin", "1.0.0", DEPENDENCIES);

        assertThat(saltedNodes(bom, "bom")).isEmpty();
    }

    /**
     * The key sequences the documents actually emit. Evidence rather than guard: it pins the
     * shape a consumer reads, while the walk above is what makes the property hold. The three
     * declared orders are jointly unreachable — one salt drives all of them, and no salt value
     * produces all three at once — so this is red on every pre-fix boot too.
     */
    @Test
    void bothDocumentsEmitTheirKeysInTheDeclaredOrder() {
        AppManifest manifest = exampleApp();

        Map<String, Object> evidence = new ReleaseEvidence().build(manifest,
                "com.example.user-admin", "1.0.0");
        assertThat(keys((Map<?, ?>) evidence.get("app"))).containsExactly("name", "version");

        Map<String, Object> bom = new SbomGenerator().cycloneDx(manifest, "user-admin", "1.0.0",
                DEPENDENCIES);
        Map<?, ?> metadata = (Map<?, ?>) bom.get("metadata");
        assertThat(keys((Map<?, ?>) metadata.get("component")))
                .containsExactly("type", "name", "version");

        List<?> components = (List<?>) bom.get("components");
        Map<?, ?> firstHash = (Map<?, ?>) ((List<?>) ((Map<?, ?>) components.get(0))
                .get("hashes")).get(0);
        assertThat(keys(firstHash)).containsExactly("alg", "content");
    }
}
