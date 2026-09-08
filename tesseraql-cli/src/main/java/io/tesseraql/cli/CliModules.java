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
     * {@code --modules} directory onto the thread context classloader, and installs the
     * {@link ExpressionFunctions} registry and the module drivers from it. Custom expression
     * functions must be installed before parsing, so the authoring commands that read an
     * application — {@code lint}, {@code test}, {@code coverage}, {@code routes}, {@code job},
     * {@code duckdb} — call this first. A broken app (unreadable manifest) installs nothing and
     * does not fail here: the linter reports the manifest problem itself.
     *
     * <p>Mutating process-global state is correct here by construction: one CLI invocation is
     * one application. A process that serves several — {@code dev}, the gateway — must not use
     * this; it builds one loader per runtime through {@code appLoader}/{@code AppModules},
     * because docs/stack-architecture.md decision 28 rejects a single union classloader over
     * every application's modules, which leaks functions and drivers between applications.
     */
    public static void installAppExtensions(Path app, File explicitModules) {
        ClassLoader loader = classLoaderOver(moduleDirs(app, explicitModules),
                Thread.currentThread().getContextClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        ExpressionFunctions.install(loader);
        ModuleDrivers.register(loader);
    }

    /** The resolved module cache and an explicit {@code --modules} directory, in that order. */
    private static List<File> moduleDirs(Path app, File explicitModules) {
        List<File> dirs = new ArrayList<>();
        moduleCache(app).ifPresent(dirs::add);
        if (explicitModules != null) {
            dirs.add(explicitModules);
        }
        return dirs;
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
        // The parent is this class's loader, not the thread context loader: that difference from
        // installAppExtensions is the whole reason both methods exist.
        return classLoaderOver(moduleDirs(app, explicitModules), CliModules.class.getClassLoader());
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
