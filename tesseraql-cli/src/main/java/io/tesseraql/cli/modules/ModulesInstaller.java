package io.tesseraql.cli.modules;

import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves the declared {@code tesseraql.modules} into the app's module cache
 * ({@code work/modules}) and reconciles {@code modules.lock} (design: app-developer-distribution
 * work item 4). {@code serve} calls it to load the declared modules on start (verifying the lock);
 * {@code tesseraql modules add/resolve} calls it to (re)write the lock.
 */
public final class ModulesInstaller {

    /**
     * The TesseraQL BOM coordinate that supplies module versions. Tracks the framework version (the
     * same literal the CLI reports); resolution of unversioned coordinates needs the BOM reachable
     * (published, or in the local repository when built from the monorepo).
     */
    public static final String BOM_COORDINATE = "io.tesseraql:tesseraql-bom:0.2.0-SNAPSHOT";

    private final boolean offline;

    public ModulesInstaller() {
        this(false);
    }

    public ModulesInstaller(boolean offline) {
        this.offline = offline;
    }

    /** The outcome: the module cache directory and the resolved closure. */
    public record Result(Path cacheDir, List<ResolvedModule> artifacts, boolean wroteLock) {
    }

    /**
     * Resolves the declared modules into {@code appHome/work/modules}. When a {@code modules.lock}
     * exists and {@code writeLock} is false (the {@code serve} path), the resolved closure is
     * verified against it. When {@code writeLock} is true (the {@code modules} command path), a new
     * lock is written. Returns empty when no modules are declared.
     */
    public Optional<Result> install(Path appHome, AppConfig config, boolean writeLock) {
        List<ModuleCoordinate> declared = ModulesYaml.declared(config);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        List<ResolvedModule> resolved = new ModuleResolver(BOM_COORDINATE, offline)
                .resolve(declared);

        Path lockFile = appHome.resolve("modules.lock");
        if (!writeLock) {
            ModulesLock.read(lockFile).map(lock -> lock.verify(resolved)).ifPresent(problems -> {
                if (!problems.isEmpty()) {
                    throw new IllegalStateException("modules.lock verification failed:\n  "
                            + String.join("\n  ", problems)
                            + "\nRun 'tesseraql modules resolve' to refresh the lock.");
                }
            });
        }

        Path cache = appHome.resolve("work").resolve("modules");
        copyToCache(resolved, cache);
        boolean wroteLock = false;
        if (writeLock) {
            ModulesLock.from(declared, resolved).write(lockFile);
            wroteLock = true;
        }
        return Optional.of(new Result(cache, resolved, wroteLock));
    }

    /** Replaces the cache's jars with the freshly resolved closure. */
    private static void copyToCache(List<ResolvedModule> resolved, Path cache) {
        try {
            Files.createDirectories(cache);
            try (Stream<Path> existing = Files.list(cache)) {
                for (Path path : (Iterable<Path>) existing::iterator) {
                    if (path.getFileName().toString().endsWith(".jar")) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            for (ResolvedModule module : resolved) {
                Files.copy(module.file(), cache.resolve(module.file().getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
