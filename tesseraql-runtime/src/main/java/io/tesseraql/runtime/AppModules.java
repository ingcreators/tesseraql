package io.tesseraql.runtime;

import io.tesseraql.core.expr.ExpressionFunctions;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One application's modules, loaded and owned by its runtime (docs/module-scope.md): a
 * classloader over the jars its resolution left in {@code work/modules} — plus an optional
 * development override directory — and the {@link ExpressionFunctions} discovered from it. The
 * runtime that builds this closes it, after its pools; module visibility equals runtime scope,
 * so two applications in one stack see exactly their own declarations
 * (docs/stack-architecture.md decision 28).
 *
 * <p>The runtime never resolves: {@code dev} resolves before starting runtimes and a production
 * {@code host} boots offline from what install-time resolution left on disk — the host refuses
 * to start an application whose declared modules were never resolved, rather than running it
 * silently without them.
 */
final class AppModules implements AutoCloseable {

    private final URLClassLoader loader;
    private final ExpressionFunctions functions;

    private AppModules(URLClassLoader loader, ExpressionFunctions functions) {
        this.loader = loader;
        this.functions = functions;
    }

    /**
     * Loads the application's modules — from the set its package carries
     * ({@code .tesseraql/modules}) when it has one, otherwise from {@code work/modules} under its
     * work home — composed with {@code extraModules} when the development override is set. Neither
     * present → no child loader, built-ins only, nothing to close.
     *
     * <p>The two directories are never composed (docs/module-channel.md decision 3). An installed
     * application's bundled set was resolved from {@code modules.lock} and verified when the
     * archive was built; a {@code work/modules} left behind on the same host by an earlier resolve
     * is not that set, and letting it join — or shadow — would make the running closure depend on
     * what a machine happened to have done before. A source tree has no bundled directory and
     * reads {@code work/modules} as it always did.
     */
    static AppModules load(Path appHome, io.tesseraql.yaml.config.AppConfig config,
            File extraModules) {
        List<File> dirs = new ArrayList<>();
        File bundled = io.tesseraql.yaml.config.WorkHome.bundledModules(appHome).toFile();
        dirs.add(!jars(bundled).isEmpty()
                ? bundled
                : io.tesseraql.yaml.config.WorkHome.resolve(appHome, config)
                        .resolve("modules").toFile());
        if (extraModules != null) {
            dirs.add(extraModules);
        }
        List<URL> urls = new ArrayList<>();
        for (File dir : dirs) {
            for (File jar : jars(dir)) {
                try {
                    urls.add(jar.toURI().toURL());
                } catch (MalformedURLException ex) {
                    throw new IllegalArgumentException(
                            "Cannot address module jar " + jar, ex);
                }
            }
        }
        if (urls.isEmpty()) {
            return new AppModules(null, ExpressionFunctions.builtInsOnly());
        }
        URLClassLoader loader = new URLClassLoader("tesseraql-modules",
                urls.toArray(new URL[0]), AppModules.class.getClassLoader());
        try {
            return new AppModules(loader, ExpressionFunctions.load(loader));
        } catch (RuntimeException | Error ex) {
            // Function discovery over a broken jar (a ServiceConfigurationError names its
            // descriptor) must not strand the loader it just opened over that jar.
            try {
                loader.close();
            } catch (IOException ignored) {
                // The discovery failure is the one to rethrow.
            }
            throw ex;
        }
    }

    /** The jars of one directory in stable (sorted) classpath order; absent dir → none. */
    private static List<File> jars(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        Arrays.sort(files);
        return List.of(files);
    }

    /** The functions this application's expressions parse and evaluate with. */
    ExpressionFunctions functions() {
        return functions;
    }

    /**
     * The classloader module-provided SPIs resolve against, or the runtime's own when the
     * application has no modules.
     */
    ClassLoader loader() {
        return loader != null ? loader : AppModules.class.getClassLoader();
    }

    /** Whether a child loader over module jars exists at all. */
    boolean present() {
        return loader != null;
    }

    @Override
    public void close() {
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException ex) {
                // Nothing actionable at shutdown; the loader's files unmap with the process.
            }
        }
    }
}
