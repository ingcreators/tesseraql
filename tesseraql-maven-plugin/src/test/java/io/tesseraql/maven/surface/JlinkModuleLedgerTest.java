package io.tesseraql.maven.surface;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The jlinked images carry the modules the framework's own classes reach for.
 *
 * <p>{@code .github/workflows/jpackage.yml} builds the CLI and host images with a fixed
 * {@code --add-modules} list, and nothing checked it against the code. It was missing
 * {@code jdk.jfr}, which {@code JfrPinningSource} imports — so an operator who set the documented
 * {@code tesseraql.diagnostics.pinning.enabled} on either image got a {@code NoClassDefFoundError}
 * while the runtime pools were being built, with no fallback path.
 *
 * <p><b>The assertion is one-way.</b> It never claims the list is minimal, only that nothing the
 * framework's own compiled classes reach for is missing from it. That direction is the only one a
 * scan can honestly take: most of the listed roots are reached by name rather than by reference —
 * {@code jdk.localedata} through {@code Locale.forLanguageTag}, {@code jdk.crypto.ec} through a TLS
 * cipher suite, {@code jdk.crypto.cryptoki} through an operator's {@code PKCS11} keystore type —
 * and leave no constant-pool trace at all. A green run is not evidence that any entry is
 * unnecessary, and the failure message says so.
 *
 * <p><b>The dependency half is unguarded.</b> A third-party jar's need is invisible here, and it is
 * not hypothetical: netty's {@code PlatformDependent} references {@code jdk.jfr.FlightRecorder}, so
 * {@code jdk.jfr} was owed twice over. See docs/release-and-ci-hardening.md, decision 3.
 *
 * <p>This guard lives in {@code tesseraql-maven-plugin} rather than {@code tesseraql-docs-reference}
 * for the reason {@link YamlSurfaceConsumerGuardTest} does: this module builds 30th of 30, last in
 * the reactor, so every sibling's {@code target/classes} exists. No other module can host a scan
 * over the whole tree — {@code tesseraql-docs-reference} depends on both images and so builds late,
 * but not last.
 */
class JlinkModuleLedgerTest {

    /**
     * Modules that ship in neither image, so a JDK module they reach for is not the images' problem.
     * Nothing in the reactor depends on either, so neither is inside a fat jar.
     */
    private static final Set<String> NOT_IN_ANY_IMAGE = Set.of("tesseraql-maven-plugin",
            "tesseraql-docs-reference");

    private static final Path REACTOR = Path.of(System.getProperty("user.dir")).getParent();

    private static List<Path> classesDirs;

    @BeforeAll
    static void collectTheReactorsClasses() throws IOException {
        classesDirs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> modules = Files.list(REACTOR)) {
            for (Path module : modules
                    .filter(p -> p.getFileName().toString().startsWith("tesseraql-"))
                    .filter(p -> !NOT_IN_ANY_IMAGE.contains(p.getFileName().toString()))
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
        // Fail, never skip: this module builds last, so a missing sibling means the guard is being
        // run in a way that cannot see the code it exists to check.
        assertThat(missing)
                .as("target/classes missing - build the full reactor before this guard")
                .isEmpty();
        assertThat(classesDirs).as("no reactor classes to scan").isNotEmpty();
    }

    /**
     * The index and the scanner both work — asserted before anything is trusted to them.
     *
     * <p>Every way this guard can go blind produces a green run: an index that cannot see the
     * package, a scanner that matches nothing, a workflow with no {@code --add-modules} line. So
     * each is checked directly. The two canary packages are the ones the guard exists for, and one
     * of them ({@code com.sun.net.httpserver}) is reachable only through a fully-qualified
     * reference in {@code CaptureServer}, which is what makes it a scanner test as well as an index
     * test.
     */
    @Test
    void theIndexAndTheScannerBothSeeWhatTheyMust() {
        assertThat(JlinkModuleScan.moduleOf("jdk.jfr.consumer"))
                .as("the system package index must resolve the package this guard exists for; "
                        + "a JDK whose index cannot see it would pass every assertion below")
                .isEqualTo("jdk.jfr");
        assertThat(JlinkModuleScan.moduleOf("com.sun.net.httpserver")).isEqualTo("jdk.httpserver");
        assertThat(JlinkModuleScan.insideJavaSe("jdk.jfr")).isFalse();
        assertThat(JlinkModuleScan.insideJavaSe("java.sql")).isTrue();
        // java.se's closure is resolved rather than derived from the name: this one is outside it.
        assertThat(JlinkModuleScan.insideJavaSe("java.smartcardio")).isFalse();

        Set<String> reached = JlinkModuleScan.modulesBeyondJavaSe(classesDirs).keySet();
        assertThat(reached)
                .as("the bytecode scan found no module outside java.se; it is broken, not clean - "
                        + "jdk.jfr is imported by JfrPinningSource and jdk.httpserver is reached by "
                        + "a fully-qualified reference in CaptureServer")
                .contains("jdk.jfr", "jdk.httpserver");
    }

    /** Both jlink invocations declare the same module list, and there are exactly two of them. */
    @Test
    void bothImagesAreBuiltFromTheSameModuleList() throws IOException {
        List<Set<String>> lists = addModulesLists();
        assertThat(lists)
                .as("expected exactly two --add-modules lists in jpackage.yml, one per image; "
                        + "a refactor that moved or removed them would leave this guard green")
                .hasSize(2);
        assertThat(lists.get(0))
                .as("the CLI image and the host image must be linked from the same modules")
                .isEqualTo(lists.get(1));
    }

    /**
     * Every module the framework's own classes reach for is named in the images' module list.
     */
    @Test
    void everyModuleTheCodeReachesForIsLinkedIntoTheImages() throws IOException {
        Set<String> linked = addModulesLists().get(0);
        var reached = JlinkModuleScan.modulesBeyondJavaSe(classesDirs);

        var missing = new TreeMap<String, Set<String>>();
        reached.forEach((module, packages) -> {
            if (!linked.contains(module)) {
                missing.put(module, packages);
            }
        });

        assertThat(missing)
                .as("modules the reactor's compiled classes reference but the jlinked images do not "
                        + "carry; add each to BOTH --add-modules lists in .github/workflows/"
                        + "jpackage.yml. This check is one-way: it never says a listed module is "
                        + "unnecessary, because most of them are reached by name (a locale, a "
                        + "cipher suite, a keystore type) and leave no trace to scan")
                .isEmpty();
    }

    /**
     * The {@code --add-modules} values, one set per occurrence.
     *
     * <p>Found by the token, never by line number: the two lines moved by seven when job timeouts
     * landed. Parsed as a set of comma-separated names rather than tested with {@code contains},
     * because JDK 25 ships prefix pairs — {@code jdk.management} against {@code jdk.management.jfr},
     * {@code java.sql} against {@code java.sql.rowset} — where a substring test passes on the wrong
     * module. The optional {@code =} form is stripped so it cannot become a bogus first token.
     */
    private static List<Set<String>> addModulesLists() throws IOException {
        List<Set<String>> lists = new ArrayList<>();
        for (String line : Files.readAllLines(REACTOR.resolve(".github/workflows/jpackage.yml"))) {
            int at = line.indexOf("--add-modules");
            if (at < 0) {
                continue;
            }
            String value = line.substring(at + "--add-modules".length()).trim();
            if (value.startsWith("=")) {
                value = value.substring(1).trim();
            }
            // The line ends in a shell continuation; the module list is its first word.
            String names = value.split("\\s+")[0];
            Set<String> modules = new LinkedHashSet<>();
            for (String name : names.split(",")) {
                if (!name.isBlank()) {
                    modules.add(name.trim());
                }
            }
            lists.add(new TreeSet<>(modules));
        }
        return lists;
    }
}
