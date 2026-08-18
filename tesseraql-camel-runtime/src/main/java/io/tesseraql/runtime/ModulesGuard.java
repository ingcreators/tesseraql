package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Hashing;
import io.tesseraql.operations.app.InstalledApp;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The host's module refusals, raised per application before any runtime boots
 * (docs/module-scope.md): a production {@code host} runs offline from what resolution left in
 * {@code work/modules}, so the state that used to be silent — declared modules with nothing on
 * disk, or jars that no longer match the lock — refuses the start and names the fix instead of
 * running the application without the functions, codecs and drivers it declared.
 */
final class ModulesGuard {

    /**
     * TQL-APP-4216: an application declares {@code tesseraql.modules} and its
     * {@code work/modules} holds no jars.
     *
     * <p>Module resolution reaches Maven repositories, so it never happens at host start — run
     * {@code tesseraql modules resolve} against the installed application (or install a package
     * whose modules were resolved) and start again. Starting anyway would run the application
     * without the expression functions, codecs and drivers it declared: routes referencing them
     * would fail at parse, or worse, quietly bind to nothing.
     */
    static final TqlErrorCode MODULES_UNRESOLVED = new TqlErrorCode(TqlDomain.APP, 4216);

    /**
     * TQL-APP-4217: the jars in an application's {@code work/modules} disagree with its
     * {@code modules.lock}.
     *
     * <p>The lock pins the resolved closure by checksum; a jar the lock does not name, a locked
     * artifact that is absent, or a checksum mismatch means the directory is not what resolution
     * produced. Re-run {@code tesseraql modules resolve} to make the lock and the directory
     * agree — the host will not guess which of the two is the intended truth.
     */
    static final TqlErrorCode MODULES_DIVERGED = new TqlErrorCode(TqlDomain.APP, 4217);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModulesGuard() {
    }

    /**
     * Refuses the start when any application's declared modules are unresolved on disk, or
     * resolved into a directory its lock disagrees with. An application declaring no modules
     * passes untouched; an absent lock is accepted (it is optional).
     */
    static void requireResolved(Path installRoot, List<InstalledApp> applications,
            Map<String, io.tesseraql.yaml.config.AppConfig> configs) {
        for (InstalledApp app : applications) {
            io.tesseraql.yaml.config.AppConfig config = configs.get(app.name());
            if (!(config.navigate("tesseraql.modules") instanceof List<?> declared)
                    || declared.isEmpty()) {
                continue;
            }
            Path appHome = installRoot.resolve(app.path()).normalize();
            List<Path> jars = jars(io.tesseraql.yaml.config.WorkHome
                    .resolve(appHome, config).resolve("modules"));
            if (jars.isEmpty()) {
                throw new TqlException(MODULES_UNRESOLVED, "Application '" + app.name()
                        + "' declares " + declared.size() + " module(s) under tesseraql.modules"
                        + " but its work/modules holds no jars — run 'tesseraql modules"
                        + " resolve' against " + appHome + " before hosting it");
            }
            verifyAgainstLock(app.name(), appHome, jars);
        }
    }

    /** Compares the directory's jar checksums with the lock's, when a lock exists. */
    private static void verifyAgainstLock(String appName, Path appHome, List<Path> jars) {
        Path lock = appHome.resolve("modules.lock");
        if (!Files.isRegularFile(lock)) {
            return;
        }
        List<String> locked = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(Files.readString(lock));
            root.path("artifacts").forEach(node -> locked.add(node.path("sha256").asText()));
        } catch (IOException ex) {
            throw new TqlException(MODULES_DIVERGED, "Application '" + appName
                    + "' has an unreadable modules.lock (" + ex.getMessage()
                    + ") — re-run 'tesseraql modules resolve' against " + appHome);
        }
        List<String> present = new ArrayList<>(jars.stream().map(Hashing::sha256).toList());
        List<String> expected = new ArrayList<>(locked);
        expected.sort(String::compareTo);
        present.sort(String::compareTo);
        if (!expected.equals(present)) {
            throw new TqlException(MODULES_DIVERGED, "Application '" + appName
                    + "' has " + jars.size() + " jar(s) in work/modules that disagree with"
                    + " modules.lock (" + locked.size() + " locked artifact(s)) — re-run"
                    + " 'tesseraql modules resolve' against " + appHome + " so the lock and"
                    + " the directory agree");
        }
    }

    private static List<Path> jars(Path dir) {
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        Arrays.sort(files);
        return Arrays.stream(files).map(File::toPath).toList();
    }
}
