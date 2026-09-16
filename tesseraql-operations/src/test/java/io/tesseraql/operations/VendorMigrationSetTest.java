package io.tesseraql.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every vendor directory holds the same migration file names.
 *
 * <p>A missing vendor variant is invisible at runtime, which is what makes this worth a test rather
 * than a review habit. {@code SqlScripts.applyForVendor} falls back to the common script per file,
 * and Flyway picks a location by probing for {@code V1} — so a forgotten
 * {@code operations-oracle/V14} does not fail anything: it silently applies the common statements
 * where the vendor needed its own, or freezes that vendor's Flyway history one version back. The
 * failure surfaces later, on a database nobody runs per pull request.
 *
 * <p>The names must match, not the contents. A vendor file exists precisely because its statements
 * differ.
 */
class VendorMigrationSetTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/tesseraql/db/migration");

    /**
     * No vendor directory is allowed a partial set. Two were, once: {@code totp} and
     * {@code workflow-task} each shipped a {@code V2} with no Oracle or SQL Server file while
     * their {@code V1} and {@code V3} had both, and the common script silently ran in the gap.
     * Both turned out wrong there — the {@code add column} keyword failed every workflow-app
     * boot on both vendors, and the common {@code timestamp} was a SQL Server rowversion that
     * refused every recovery-code insert (docs/audit-low-leads.md slices 2b and 4). The
     * exemption list that recorded them is gone with them; a new gap fails here.
     */
    @Test
    void everyVendorDirectoryHoldsTheSameMigrationNames() throws IOException {
        try (Stream<Path> directories = Files.list(MIGRATIONS)) {
            directories.filter(Files::isDirectory)
                    .filter(path -> !isVendorVariant(path.getFileName().toString()))
                    .forEach(VendorMigrationSetTest::assertVariantsMatch);
        }
    }

    private static void assertVariantsMatch(Path common) {
        String component = common.getFileName().toString();
        Set<String> expected = scriptNames(common);
        for (String vendor : new String[]{"oracle", "sqlserver"}) {
            String name = component + "-" + vendor;
            Path variant = common.resolveSibling(name);
            if (!Files.isDirectory(variant)) {
                // Not every component needs a vendor variant at all; only a partial one is a bug.
                continue;
            }
            assertThat(scriptNames(variant))
                    .as("%s-%s must carry the same migration names as %s — a missing variant is"
                            + " silent: the common script is applied in its place and that"
                            + " vendor's Flyway history stops one version short",
                            component, vendor, component)
                    .isEqualTo(expected);
        }
    }

    private static boolean isVendorVariant(String directory) {
        return directory.endsWith("-oracle") || directory.endsWith("-sqlserver");
    }

    private static Set<String> scriptNames(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
