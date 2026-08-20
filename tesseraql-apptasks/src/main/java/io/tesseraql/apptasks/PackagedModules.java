package io.tesseraql.apptasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Hashing;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The packaging side of the module channel (docs/module-channel.md decision 3): what a
 * {@code .tqlapp} must carry, and the two refusals that keep it honest.
 *
 * <p>Packaging is the last moment a resolver is present — a deployment never resolves artifacts —
 * so it is where the module closure is settled. The lock is what makes that deterministic: it pins
 * exact coordinates and checksums, so resolving at pack time reproduces, while packaging with no
 * lock at all does not. Writing the lock ({@code tesseraql modules resolve}) is the reviewable
 * human act; everything after it is mechanical.
 */
public final class PackagedModules {

    /**
     * TQL-APP-4218: an application declares {@code tesseraql.modules} and has no
     * {@code modules.lock}.
     *
     * <p>Packaging resolves the declared closure, and the lock is what says which closure was
     * reviewed. Without it the archive's contents would depend on what a repository served at
     * build time — run {@code tesseraql modules resolve} and commit the lock beside the
     * declaration. The jars themselves belong in neither the repository nor the lock: they are
     * resolved into a work tree and carried by the package.
     */
    public static final TqlErrorCode MODULES_UNLOCKED = new TqlErrorCode(TqlDomain.APP, 4218);

    /**
     * TQL-APP-4219: the closure resolved at pack time disagrees with {@code modules.lock}.
     *
     * <p>The pack-time twin of the host's {@code TQL-APP-4217}: a jar the lock does not name, a
     * locked artifact that did not resolve, or a checksum mismatch means the declaration and the
     * lock have drifted apart, or a repository served something else. Re-run
     * {@code tesseraql modules resolve} so the two agree — packaging will not guess which is the
     * intended truth, because the archive it would produce is the thing deployments trust.
     */
    public static final TqlErrorCode MODULES_DIVERGED_AT_PACK = new TqlErrorCode(TqlDomain.APP,
            4219);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackagedModules() {
    }

    /** Whether {@code config} declares a non-empty {@code tesseraql.modules} list. */
    public static boolean declared(AppConfig config) {
        return config.navigate("tesseraql.modules") instanceof List<?> modules
                && !modules.isEmpty();
    }

    /**
     * The application's {@code modules.lock}, or {@code TQL-APP-4218} when it declares modules
     * without one. An application declaring no modules yields empty and packs unchanged.
     */
    public static java.util.Optional<Path> requireLock(Path appHome, AppConfig config) {
        if (!declared(config)) {
            return java.util.Optional.empty();
        }
        Path lock = appHome.resolve("modules.lock");
        if (!Files.isRegularFile(lock)) {
            throw new TqlException(MODULES_UNLOCKED, "Application '" + appHome.getFileName()
                    + "' declares tesseraql.modules but has no modules.lock, so packaging cannot"
                    + " say which closure was reviewed — run 'tesseraql modules resolve --app "
                    + appHome + "' and commit the lock beside the declaration");
        }
        return java.util.Optional.of(lock);
    }

    /**
     * Refuses ({@code TQL-APP-4219}) when the jars in {@code modulesDir} are not the closure
     * {@code lock} names, comparing SHA-256 the way the host's own guard does.
     */
    public static void verifyAgainstLock(Path appHome, Path modulesDir, Path lock) {
        List<String> locked = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(Files.readString(lock));
            root.path("artifacts").forEach(node -> locked.add(node.path("sha256").asText()));
        } catch (IOException ex) {
            throw new TqlException(MODULES_DIVERGED_AT_PACK, "Application '"
                    + appHome.getFileName() + "' has an unreadable modules.lock ("
                    + ex.getMessage() + ") — re-run 'tesseraql modules resolve --app " + appHome
                    + "'");
        }
        List<String> present = new ArrayList<>(jars(modulesDir).stream()
                .map(Hashing::sha256).toList());
        List<String> expected = new ArrayList<>(locked);
        expected.sort(String::compareTo);
        present.sort(String::compareTo);
        if (!expected.equals(present)) {
            throw new TqlException(MODULES_DIVERGED_AT_PACK, "Application '"
                    + appHome.getFileName() + "' resolved " + present.size()
                    + " module jar(s) that disagree with modules.lock (" + locked.size()
                    + " locked artifact(s)) — re-run 'tesseraql modules resolve --app " + appHome
                    + "' so the declaration and the lock agree");
        }
    }

    /**
     * The {@code group:artifact:version} coordinates {@code lock} pins, in file order — what a
     * build that has no TesseraQL resolver of its own (the Maven plugin, resolving through Maven)
     * needs in order to fetch exactly the reviewed closure and nothing else.
     */
    public static List<String> lockedCoordinates(Path lock) {
        List<String> coordinates = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(Files.readString(lock));
            root.path("artifacts").forEach(node -> coordinates.add(
                    node.path("coordinate").asText()));
        } catch (IOException ex) {
            throw new TqlException(MODULES_DIVERGED_AT_PACK, "Unreadable modules.lock at " + lock
                    + " (" + ex.getMessage() + ") — re-run 'tesseraql modules resolve'");
        }
        return coordinates;
    }

    /** The {@code *.jar} files in {@code dir}, or an empty list when it holds none. */
    public static List<Path> jars(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
