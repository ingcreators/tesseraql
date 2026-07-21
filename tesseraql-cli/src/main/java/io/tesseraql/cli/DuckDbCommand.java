package io.tesseraql.cli;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql duckdb}: offline-first provisioning for the analytics engine's extensions
 * (docs/duckdb.md). {@code install-extensions} resolves every extension the app's duckdb
 * datasources declare into the local cache — through the engine's own {@code INSTALL}, so
 * signature verification and the repository layout are DuckDB's, not ours — and can bundle the
 * cache for an air gap; {@code info} reports the pin and what the cache holds. The runtime never
 * downloads: a declared extension missing from this cache fails the boot fast (TQL-APP-4204).
 */
@Command(name = "duckdb", description = "Provision and inspect the analytics engine's offline extension cache.", subcommands = {
        DuckDbCommand.InstallExtensionsCommand.class,
        DuckDbCommand.InfoCommand.class})
final class DuckDbCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Usage: tesseraql duckdb (install-extensions|info) --app <dir>");
        return 2;
    }

    /** The extension cache directory (mirrors the runtime's resolution). */
    static Path extensionDirectory(AppConfig config, Path app) {
        return config.getString("tesseraql.duckdb.extensionDirectory")
                .map(app::resolve)
                .orElseGet(() -> app.resolve("work/duckdb-extensions"))
                .normalize()
                .toAbsolutePath();
    }

    /** Every extension any duckdb datasource declares, in declaration order. */
    static Set<String> declaredExtensions(AppConfig config) {
        Set<String> extensions = new LinkedHashSet<>();
        if (config.navigate("tesseraql.datasources") instanceof Map<?, ?> datasources) {
            for (Object name : datasources.keySet()) {
                Object declared = config.navigate(
                        "tesseraql.datasources." + name + ".duckdb.extensions");
                if (declared instanceof List<?> list) {
                    list.forEach(entry -> extensions.add(String.valueOf(entry)));
                }
            }
        }
        return extensions;
    }

    /** A connection to the embedded engine, with the module classloader's driver visible. */
    static Connection engine(Path app, Path extensionDirectory) throws SQLException {
        CliModules.installAppExtensions(app, null);
        Properties properties = new Properties();
        properties.setProperty("extension_directory", extensionDirectory.toString());
        try {
            return DriverManager.getConnection("jdbc:duckdb:", properties);
        } catch (SQLException noDriver) {
            throw new SQLException("The DuckDB driver is not available. Add it to the app first:"
                    + " tesseraql modules add org.duckdb:duckdb_jdbc --app .", noDriver);
        }
    }

    /** The engine's version tag and platform, e.g. {@code v1.3.1} / {@code linux_amd64}. */
    static String[] versionAndPlatform(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT version(), (SELECT platform FROM pragma_platform())")) {
            rs.next();
            return new String[]{rs.getString(1), rs.getString(2)};
        }
    }

    /** {@code tesseraql duckdb install-extensions --app <dir>}. */
    @Command(name = "install-extensions", description = "Resolve declared engine extensions into the offline cache (or from/into a bundle).")
    static final class InstallExtensionsCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the app home.")
        Path app;

        @Option(names = {
                "--repository"}, description = "Extension repository URL (a corporate mirror); default is DuckDB's.")
        String repository;

        @Option(names = {
                "--bundle"}, description = "Also write the cache as a portable zip for air-gapped provisioning.")
        Path bundle;

        @Option(names = {
                "--from-bundle"}, description = "Populate the cache from a bundle zip instead of the network.")
        Path fromBundle;

        @Override
        public Integer call() throws Exception {
            AppConfig config = new ManifestLoader().load(app).config();
            Path cache = extensionDirectory(config, app);
            Files.createDirectories(cache);
            if (fromBundle != null) {
                unzip(fromBundle, cache);
                System.out.println("Extension cache populated from " + fromBundle + " at " + cache);
                return 0;
            }
            Set<String> extensions = declaredExtensions(config);
            if (extensions.isEmpty()) {
                System.out.println("No duckdb datasource declares extensions; nothing to install.");
                return 0;
            }
            try (Connection connection = engine(app, cache)) {
                String[] pin = versionAndPlatform(connection);
                System.out.println("Engine " + pin[0] + " on " + pin[1] + "; cache " + cache);
                for (String extension : extensions) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(repository == null
                                ? "INSTALL " + extension
                                : "INSTALL " + extension + " FROM '"
                                        + repository.replace("'", "''") + "'");
                    }
                    System.out.println("Installed " + extension);
                }
            }
            if (bundle != null) {
                zip(cache, bundle);
                System.out.println("Bundle written to " + bundle
                        + " (unpack on the target with --from-bundle)");
            }
            return 0;
        }
    }

    /** {@code tesseraql duckdb info --app <dir>}. */
    @Command(name = "info", description = "Report the engine pin, the cache location, and which declared extensions it holds.")
    static final class InfoCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the app home.")
        Path app;

        @Override
        public Integer call() throws Exception {
            AppConfig config = new ManifestLoader().load(app).config();
            Path cache = extensionDirectory(config, app);
            Set<String> declared = declaredExtensions(config);
            System.out.println("Extension cache: " + cache);
            try (Connection connection = engine(app, cache)) {
                String[] pin = versionAndPlatform(connection);
                System.out.println("Engine: " + pin[0] + " on " + pin[1]);
            } catch (SQLException noDriver) {
                System.out.println("Engine: driver not available ("
                        + "tesseraql modules add org.duckdb:duckdb_jdbc --app .)");
            }
            System.out.println("Declared extensions: "
                    + (declared.isEmpty() ? "(none)" : String.join(", ", declared)));
            if (Files.isDirectory(cache)) {
                try (Stream<Path> files = Files.walk(cache)) {
                    files.filter(f -> f.toString().endsWith(".duckdb_extension"))
                            .sorted()
                            .forEach(f -> System.out.println("Cached: " + cache.relativize(f)));
                }
            }
            return 0;
        }
    }

    /** Zips {@code cache} (relative layout preserved) into {@code bundle}. */
    static void zip(Path cache, Path bundle) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle));
                Stream<Path> files = Files.walk(cache)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(
                        cache.relativize(file).toString().replace('\\', '/')));
                try (InputStream in = Files.newInputStream(file)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    /** Unzips {@code bundle} into {@code cache}, refusing entries that escape it. */
    static void unzip(Path bundle, Path cache) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = cache.resolve(entry.getName()).normalize();
                if (!target.startsWith(cache)) {
                    throw new IOException("Bundle entry escapes the cache: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    zip.transferTo(out);
                }
            }
        }
    }
}
