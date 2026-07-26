package io.tesseraql.maven.surface;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The YAML-surface consumer guard (docs/yaml-surface-consumers.md, slice 2): every record
 * component an app author can write must have a behavioral consumer, or a deliberate
 * registry entry saying why not. Lives in the reactor's terminal module because the scan
 * needs every sibling's compiled classes — and when they are missing it fails rather than
 * skips: a guard that goes quiet when run the wrong way is the failure mode the design
 * document is about.
 *
 * <p>This guard was proven able to fail before it was trusted: adding a dead
 * {@code deadCanary} component to {@code PollSpec} turns the build red.
 */
class YamlSurfaceConsumerGuardTest {

    /** Consumers that only render: reads from these do not make a component behavioral. */
    private static final List<String> DISPLAY_PACKAGES = List.of("io/tesseraql/docs/");
    private static final String DISPLAY_CLASS_MARKER = "Studio";

    private static ModelFieldConsumerScan scan;

    @BeforeAll
    static void scanTheReactor() throws IOException {
        Path reactorRoot = Path.of(System.getProperty("user.dir")).getParent();
        List<Path> classesDirs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> modules = Files.list(reactorRoot)) {
            for (Path module : modules
                    .filter(p -> p.getFileName().toString().startsWith("tesseraql-"))
                    .filter(p -> Files.isDirectory(p.resolve("src/main/java")))
                    .sorted()
                    .toList()) {
                Path classes = module.resolve("target/classes");
                if (Files.isDirectory(classes)) {
                    classesDirs.add(classes);
                } else {
                    missing.add(module.getFileName().toString());
                }
            }
        }
        // Fail, never skip: this module builds last in the reactor, so a missing sibling
        // means the guard is being run in a way that cannot see the consumers.
        assertThat(missing)
                .as("target/classes missing - build the full reactor before this guard")
                .isEmpty();
        scan = ModelFieldConsumerScan.over(classesDirs);
    }

    @Test
    void everyModelComponentIsWiredOrDeliberatelyRegistered() {
        Map<String, ModelFieldConsumerScan.Consumers> verdicts = scan.classify();
        assertThat(verdicts).as("the model package should surface record components")
                .hasSizeGreaterThan(250);

        List<String> deadAndUnregistered = new ArrayList<>();
        verdicts.forEach((component, consumers) -> {
            if (!consumers.wired() && !YamlSurfaceConsumers.UNWIRED.containsKey(component)) {
                deadAndUnregistered.add(component);
            }
        });
        assertThat(deadAndUnregistered)
                .as("parsed, accepted, and consumed by nothing - wire it or don't declare it"
                        + " (docs/yaml-surface-consumers.md)")
                .isEmpty();
    }

    @Test
    void aComponentOnlyDisplaySurfacesReadMustBeDeclaredDisplayOnly() {
        // This is the check that would have caught security.provider: it HAD consumers,
        // and all of them only printed it.
        Map<String, ModelFieldConsumerScan.Consumers> verdicts = scan.classify();
        List<String> displayOnlyUndeclared = new ArrayList<>();
        verdicts.forEach((component, consumers) -> {
            if (consumers.wired() && consumers.all().stream().allMatch(
                    YamlSurfaceConsumerGuardTest::isDisplaySurface)
                    && !YamlSurfaceConsumers.DISPLAY_ONLY.containsKey(component)) {
                displayOnlyUndeclared.add(component + " <- " + consumers.all());
            }
        });
        assertThat(displayOnlyUndeclared)
                .as("every consumer only renders this - either wire behavior or declare"
                        + " DISPLAY_ONLY with a justification")
                .isEmpty();
    }

    @Test
    void theRegistryCannotHoldWishes() {
        // An entry must name a component that exists and be true about it - a stale or
        // aspirational registration fails, keeping the registry honest, not decorative.
        Map<String, ModelFieldConsumerScan.Consumers> verdicts = scan.classify();
        YamlSurfaceConsumers.UNWIRED.forEach((component, reason) -> {
            assertThat(verdicts).as("UNWIRED entry names a declared component")
                    .containsKey(component);
            assertThat(verdicts.get(component).wired())
                    .as("UNWIRED entry '%s' is actually consumed - remove the entry",
                            component)
                    .isFalse();
            assertThat(reason).as("UNWIRED entries carry a reason").isNotBlank();
        });
        YamlSurfaceConsumers.DISPLAY_ONLY.forEach((component, justification) -> {
            assertThat(verdicts).as("DISPLAY_ONLY entry names a declared component")
                    .containsKey(component);
            ModelFieldConsumerScan.Consumers consumers = verdicts.get(component);
            assertThat(consumers.wired())
                    .as("DISPLAY_ONLY entry '%s' has no consumer at all - it is UNWIRED",
                            component)
                    .isTrue();
            assertThat(consumers.all())
                    .as("DISPLAY_ONLY entry '%s' has a behavioral consumer - unregister it",
                            component)
                    .allMatch(YamlSurfaceConsumerGuardTest::isDisplaySurface);
            assertThat(justification).as("DISPLAY_ONLY entries carry a justification")
                    .isNotBlank();
        });
    }

    @Test
    void theAttributionSeparatesCanonicalFromDerivedReads() {
        // The distinction the third correction demanded, pinned: a component read only
        // through a derived accessor is wired via that accessor's external callers, not
        // via its own canonical accessor.
        Map<String, ModelFieldConsumerScan.Consumers> verdicts = scan.classify();
        ModelFieldConsumerScan.Consumers move = verdicts.get("PollSpec#move");
        assertThat(move.direct()).isEmpty();
        assertThat(move.viaRecordMethods())
                .anySatisfy(via -> assertThat(via).contains("->effectiveMove()"));
    }

    private static boolean isDisplaySurface(String className) {
        return DISPLAY_PACKAGES.stream().anyMatch(className::startsWith)
                || className.contains(DISPLAY_CLASS_MARKER);
    }
}
