package io.tesseraql.cli;

import io.tesseraql.cli.modules.ModuleDrivers;
import io.tesseraql.cli.modules.ModulesInstaller;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads optional plugin modules from a directory of jars onto a child classloader, so the CLI can
 * run apps that use opt-in capabilities — chiefly the {@code pdf}/{@code excel} file-format codecs —
 * without those modules sitting on the CLI's own base classpath (design: docs/printable-documents.md
 * keeps {@code tesseraql-pdf}/{@code tesseraql-excel} opt-in). The codecs register through the
 * {@code FileCodec} {@link java.util.ServiceLoader} SPI, which resolves against the thread context
 * classloader; pointing that at a child loader over the modules directory is the whole mechanism.
 */
public final class CliModules {

    private CliModules() {
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
     * {@code serve} wiring, so {@code lint}/{@code test}/{@code coverage}/{@code mcp} parse and
     * evaluate the same custom functions the runtime serves. A broken app (unreadable manifest)
     * installs nothing and does not fail here: the linter reports the manifest problem itself.
     */
    public static void installAppExtensions(Path app, File explicitModules) {
        List<File> moduleDirs = new ArrayList<>();
        try {
            new ModulesInstaller()
                    .install(app, new ManifestLoader().load(app).config(), false)
                    .ifPresent(result -> moduleDirs.add(result.cacheDir().toFile()));
        } catch (RuntimeException ex) {
            // lint of a broken app must still run; modules just stay uninstalled
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
