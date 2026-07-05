package io.tesseraql.cli;

import io.tesseraql.cli.modules.ModuleCoordinate;
import io.tesseraql.cli.modules.ModuleResolver;
import io.tesseraql.cli.modules.ModulesInstaller;
import io.tesseraql.cli.modules.ResolvedModule;
import io.tesseraql.runtime.DataSources;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Starts an embedded PostgreSQL for {@code serve --embedded-db}, so a tqlapp runs with no external
 * database. The platform's PostgreSQL binary is not bundled in the CLI; it is resolved on demand
 * (pinned to {@code zonky.postgres.binaries.version}) through the same embedded ShrinkWrap resolver
 * the opt-in {@code tesseraql.modules} use, then handed to zonky as the sole {@code .txz} payload of
 * the resolved jar. Because the embedded instance is a real {@code postgres} process, the runtime's
 * Flyway migrations and {@code ensureSchema} bootstrap run unchanged — no dialect work.
 */
final class EmbeddedPostgresSupport {

    private static final String BINARIES_GROUP = "io.zonky.test.postgres";

    private EmbeddedPostgresSupport() {
    }

    /** A running embedded PostgreSQL: the {@code main} override it backs, its version, plus its shutdown. */
    record Handle(DataSources.MainDatasourceOverride override, EmbeddedPostgres postgres,
            String version)
            implements
                AutoCloseable {

        /** The TCP port the instance is listening on (the resolved value when one was random). */
        int port() {
            return postgres.getPort();
        }

        /** The JDBC URL of the instance's {@code postgres} database (trust auth, localhost only). */
        String jdbcUrl() {
            return override.jdbcUrl();
        }

        @Override
        public void close() {
            try {
                postgres.close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /** Starts an embedded instance on a random free port (see {@link #start(Path, Integer, String, boolean)}). */
    static Handle start(Path dataDir, boolean offline) {
        return start(dataDir, null, null, offline);
    }

    /** Starts an embedded instance with the default binary version (see {@link #start(Path, Integer, String, boolean)}). */
    static Handle start(Path dataDir, Integer port, boolean offline) {
        return start(dataDir, port, null, offline);
    }

    /**
     * Resolves the platform's PostgreSQL binary and starts an embedded instance. With {@code dataDir}
     * null the data directory is a fresh temp dir cleaned on close (an ephemeral dev run); otherwise
     * it persists across restarts (a single-server run). With {@code port} null the instance binds a
     * random free port chosen at startup; a non-null value pins it (so a local client has a stable
     * address). Either way it listens on localhost only — zonky's defaults ({@code listen_addresses}
     * = localhost, {@code pg_hba} trust for loopback) are left unchanged, so it is not reachable from
     * other hosts.
     *
     * <p>The zonky binaries version is chosen as: {@code requestedVersion} when non-null (an explicit
     * {@code --embedded-db-version}); otherwise the version this persistent {@code dataDir} is pinned
     * to (its {@value EmbeddedPostgresDataDir#MARKER} marker); otherwise the CLI's build-time default.
     * On a successful start the chosen version is recorded as the directory's pin, so a later bump of
     * the default never re-resolves an incompatible major against an existing directory.
     */
    static Handle start(Path dataDir, Integer port, String requestedVersion, boolean offline) {
        String version = selectVersion(dataDir, requestedVersion);
        checkMajorCompatibility(dataDir, version);
        Path binaryJar = resolveBinaryJar(EmbeddedPostgresBinary.classifier(), version, offline);
        try {
            EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                    .setPgBinaryResolver((system, hardware) -> openBinary(binaryJar));
            if (dataDir != null) {
                builder.setDataDirectory(dataDir).setCleanDataDirectory(false);
            }
            if (port != null) {
                builder.setPort(port);
            }
            EmbeddedPostgres postgres = builder.start();
            DataSources.MainDatasourceOverride override = new DataSources.MainDatasourceOverride(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
            EmbeddedPostgresDataDir.writePinnedVersion(dataDir, version);
            return new Handle(override, postgres, version);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to start embedded PostgreSQL", ex);
        }
    }

    /**
     * Fails fast when {@code version} cannot open an already-initialized {@code dataDir} because the
     * directory's on-disk major differs, turning what would be a cryptic {@code postgres} startup
     * failure into an actionable message. A fresh or ephemeral directory has nothing to conflict with.
     */
    private static void checkMajorCompatibility(Path dataDir, String version) {
        EmbeddedPostgresDataDir.onDiskMajor(dataDir).ifPresent(onDiskMajor -> {
            if (!onDiskMajor.equals(EmbeddedPostgresDataDir.majorOf(version))) {
                throw new EmbeddedPostgresVersionMismatchException(dataDir, onDiskMajor, version);
            }
        });
    }

    /** The binary version to run: an explicit request, else the directory's pin, else the default. */
    private static String selectVersion(Path dataDir, String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            return requestedVersion.trim();
        }
        return EmbeddedPostgresDataDir.pinnedVersion(dataDir)
                .orElseGet(EmbeddedPostgresSupport::binariesVersion);
    }

    private static Path resolveBinaryJar(String classifier, String version, boolean offline) {
        String coordinate = BINARIES_GROUP + ":embedded-postgres-binaries-" + classifier + ":"
                + version;
        List<ResolvedModule> resolved = new ModuleResolver(ModulesInstaller.BOM_COORDINATE, offline)
                .resolve(List.of(ModuleCoordinate.parse(coordinate)));
        return resolved.stream()
                .filter(module -> module.coordinate().contains("embedded-postgres-binaries-"))
                .map(ResolvedModule::file)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Resolved no embedded PostgreSQL binary for " + coordinate));
    }

    /**
     * Streams the single {@code .txz} payload of the resolved binary jar. A custom resolver bypasses
     * zonky's own classpath-scanning {@code DefaultPostgresBinaryResolver}, so we locate the payload
     * by extension rather than rebuilding its {@code postgres-<os>-<arch>.txz} name (the args zonky
     * passes are unnormalized, e.g. {@code Linux}/{@code x86_64}).
     */
    private static InputStream openBinary(Path binaryJar) throws IOException {
        JarFile jar = new JarFile(binaryJar.toFile());
        JarEntry entry = jar.stream()
                .filter(candidate -> candidate.getName().endsWith(".txz"))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            jar.close();
            throw new IOException("No .txz payload in embedded PostgreSQL binary jar " + binaryJar);
        }
        InputStream in = jar.getInputStream(entry);
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    jar.close();
                }
            }
        };
    }

    private static String binariesVersion() {
        try (InputStream in = EmbeddedPostgresSupport.class
                .getResourceAsStream("/io/tesseraql/cli/embedded-postgres.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing embedded-postgres.properties on the classpath");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.startsWith("$")) {
                throw new IllegalStateException("Embedded PostgreSQL binary version is unset");
            }
            return version;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
