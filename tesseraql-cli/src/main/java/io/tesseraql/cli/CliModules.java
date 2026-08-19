package io.tesseraql.cli;

import io.tesseraql.cli.modules.ModuleDrivers;
import io.tesseraql.cli.modules.ModulesInstaller;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.config.WorkHome;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Loads optional plugin modules from a directory of jars onto a child classloader, so the CLI can
 * run apps that use opt-in capabilities — chiefly the {@code pdf}/{@code excel} file-format codecs —
 * without those modules sitting on the CLI's own base classpath (design: docs/printable-documents.md
 * keeps {@code tesseraql-pdf}/{@code tesseraql-excel} opt-in). The codecs register through the
 * {@code FileCodec} {@link java.util.ServiceLoader} SPI, which resolves against the thread context
 * classloader; pointing that at a child loader over the modules directory is the whole mechanism.
 */
public final class CliModules {

    /**
     * Whether the embedded artifact resolver is on the classpath. The developer CLI carries it
     * (for {@code tesseraql.modules} and the embedded-db binary); the deployment distribution
     * deliberately does not (docs/runtime-footprint.md decision 1) — a deployment never resolves
     * artifacts, because its module caches were resolved and lock-verified before it was
     * deployed. Probed once so every module-touching path below can choose resolve-or-read.
     */
    private static final boolean RESOLVER_PRESENT = resolverPresent();

    private CliModules() {
    }

    private static boolean resolverPresent() {
        try {
            Class.forName("org.jboss.shrinkwrap.resolver.api.maven.Maven", false,
                    CliModules.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /**
     * A classloader that adds every {@code *.jar} in {@code modulesDir} as a child of {@code parent},
     * or {@code parent} unchanged when the directory is {@code null}, missing, or holds no jars.
     */
    static ClassLoader classLoader(File modulesDir, ClassLoader parent) {
        URL[] jars = jars(modulesDir);
        return jars.length == 0 ? parent : new URLClassLoader(jars, parent);
    }

    /**
     * A classloader over the jars in every directory of {@code modulesDirs} (the resolved
     * {@code tesseraql.modules} cache and an explicit {@code --modules} directory compose), or
     * {@code parent} unchanged when none hold jars.
     */
    static ClassLoader classLoaderOver(List<File> modulesDirs, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        for (File dir : modulesDirs) {
            urls.addAll(Arrays.asList(jars(dir)));
        }
        return urls.isEmpty() ? parent : new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    /**
     * Composes the app's resolved {@code tesseraql.modules} cache and an optional explicit
     * {@code --modules} directory onto the thread context classloader and installs the
     * {@link ExpressionFunctions} registry from it — the authoring-tool counterpart of the
     * {@code dev} wiring, so {@code lint}/{@code test}/{@code coverage}/{@code mcp} parse and
     * evaluate the same custom functions the runtime serves. A broken app (unreadable manifest)
     * installs nothing and does not fail here: the linter reports the manifest problem itself.
     */
    public static void installAppExtensions(Path app, File explicitModules) {
        installAppExtensions(List.of(app), explicitModules);
    }

    /**
     * The stack-spanning form: every application's resolved {@code tesseraql.modules} cache
     * composes onto one classloader, the same wiring {@code dev} boots the stack with — interim
     * until docs/stack-architecture.md decision 28 wires modules per runtime.
     */
    public static void installAppExtensions(List<Path> apps, File explicitModules) {
        List<File> moduleDirs = new ArrayList<>();
        for (Path app : apps) {
            moduleCache(app).ifPresent(moduleDirs::add);
        }
        if (explicitModules != null) {
            moduleDirs.add(explicitModules);
        }
        ClassLoader loader = classLoaderOver(moduleDirs,
                Thread.currentThread().getContextClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        ExpressionFunctions.install(loader);
        ModuleDrivers.register(loader);
    }

    /**
     * One application's module cache directory: resolved (lock-verified) through the embedded
     * resolver on the developer CLI, or read as-is from disk on the deployment distribution,
     * which carries no resolver. A broken app (unreadable manifest) yields nothing and does not
     * fail here: the linter reports the manifest problem itself.
     */
    private static Optional<File> moduleCache(Path app) {
        try {
            AppConfig config = new ManifestLoader().load(app).config();
            if (RESOLVER_PRESENT) {
                return new ModulesInstaller().install(app, config, false)
                        .map(result -> result.cacheDir().toFile());
            }
            Path cache = WorkHome.resolve(app, config).resolve("modules");
            return Files.isDirectory(cache) ? Optional.of(cache.toFile()) : Optional.empty();
        } catch (RuntimeException ex) {
            // lint of a broken app must still run; modules just stay uninstalled
            return Optional.empty();
        }
    }

    /**
     * The per-application module classloader the MCP dev tools resolve one application's
     * extensions with (docs/module-scope.md): its resolved {@code tesseraql.modules} cache and
     * an optional explicit {@code --modules} directory over the CLI's own classpath.
     */
    public static ClassLoader appLoader(Path app, File explicitModules) {
        List<File> moduleDirs = new ArrayList<>();
        moduleCache(app).ifPresent(moduleDirs::add);
        if (explicitModules != null) {
            moduleDirs.add(explicitModules);
        }
        return classLoaderOver(moduleDirs, CliModules.class.getClassLoader());
    }

    /** The {@code *.jar} files in {@code modulesDir} as URLs, sorted for a stable classpath order. */
    static URL[] jars(File modulesDir) {
        if (modulesDir == null || !modulesDir.isDirectory()) {
            return new URL[0];
        }
        File[] files = modulesDir.listFiles(
                file -> file.isFile() && file.getName().endsWith(".jar"));
        if (files == null || files.length == 0) {
            return new URL[0];
        }
        Arrays.sort(files);
        List<URL> urls = new ArrayList<>();
        for (File file : files) {
            try {
                urls.add(file.toURI().toURL());
            } catch (MalformedURLException ex) {
                throw new IllegalArgumentException("Not a loadable module jar: " + file, ex);
            }
        }
        return urls.toArray(new URL[0]);
    }
}
