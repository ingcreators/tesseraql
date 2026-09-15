package io.tesseraql.cli;

import io.tesseraql.apptasks.AppPackager;
import io.tesseraql.apptasks.PackagedModules;
import io.tesseraql.core.util.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql package --app <dir>}: packages the app home into a deterministic {@code .tqlapp}
 * archive with a sibling {@code .sha256} (design ch. 32.3) — the CLI-native form of the
 * {@code package-app} goal, over the shared {@link AppPackager}. Build-generated docs (from
 * {@code tesseraql generate}) are merged in under the reserved namespace when present.
 */
@Command(name = "package", description = "Package the app home into a deterministic .tqlapp.")
final class PackageCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    ConfigOptions configOptions;

    @Option(names = {
            "--out"}, description = "Output archive (default: <app>/work/<app-name>.tqlapp).")
    Path out;

    @Option(names = {"--generated"}, description = "Generated docs directory to merge"
            + " (default: <app>/work/generated/docs when present).")
    Path generated;

    @Override
    public Integer call() throws Exception {
        configOptions.apply();
        Path home = SingleApplication.resolve(app, "tesseraql package");
        if (home == null) {
            return 2;
        }
        Path work = io.tesseraql.yaml.config.WorkHome.resolve(home,
                io.tesseraql.yaml.manifest.ManifestLoader.configOnly(home));
        Path output = out != null
                ? out
                : work.resolve(home.getFileName() + ".tqlapp");
        Path docs = generated != null ? generated : work.resolve("generated/docs");
        Path generatedDocs = Files.isDirectory(docs) ? docs : null;
        Path modules = resolveDeclaredModules(home);
        new AppPackager().pack(home, generatedDocs, modules, output);
        // The sibling checksum lets installs verify package integrity (design ch. 49, 50).
        String sha256 = Hashing.sha256(output);
        Files.writeString(output.resolveSibling(output.getFileName() + ".sha256"), sha256 + "\n");
        System.out.println("Packaged TesseraQL app to " + output + " (sha256 " + sha256 + ")");
        return 0;
    }

    /**
     * The module closure this package carries, or null when the application declares none
     * (docs/module-channel.md decision 3). Packaging is the last moment a resolver is present, so
     * it resolves here rather than asking for a prior command: the lock pins the closure, and
     * {@link io.tesseraql.cli.modules.ModulesInstaller} verifies what it resolved against it —
     * its own refusal, the sentence {@code dev} gives the same lock. An application that declares
     * modules without a lock is refused (TQL-APP-4218) instead, because only a lock can say which
     * closure was reviewed; a lock naming an artifact the runtime carries is refused before
     * anything resolves (TQL-APP-4219), with the sentence the Maven goal gives the same lock
     * (docs/module-channel.md decision 9). Every refusal here is coded or a {@code UsageRefusal},
     * and the CLI's exception shaper prints each as its sentence with exit 2
     * (docs/cli-surface.md decision 10a).
     */
    private static Path resolveDeclaredModules(Path home) {
        io.tesseraql.yaml.config.AppConfig config = new io.tesseraql.yaml.manifest.ManifestLoader()
                .load(home).config();
        Path lock = PackagedModules.requireLock(home, config).orElse(null);
        if (lock == null) {
            return null;
        }
        PackagedModules.requireNothingTheRuntimeCarries(home, lock,
                io.tesseraql.apptasks.RuntimeClosure.fromClasspath());
        Path cache = new io.tesseraql.cli.modules.ModulesInstaller().install(home, config, false)
                .map(io.tesseraql.cli.modules.ModulesInstaller.Result::cacheDir)
                .orElse(null);
        if (cache != null) {
            PackagedModules.verifyAgainstLock(home, cache, home.resolve("modules.lock"));
        }
        return cache;
    }
}
