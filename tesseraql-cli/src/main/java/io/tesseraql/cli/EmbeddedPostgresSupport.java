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

    /** A running embedded PostgreSQL: the {@code main} override it backs, plus its shutdown. */
    record Handle(DataSources.MainDatasourceOverride override, EmbeddedPostgres postgres)
            implements
                AutoCloseable {

        @Override
        public void close() {
            try {
                postgres.close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * Resolves the platform's PostgreSQL binary and starts an embedded instance. With {@code dataDir}
     * null the data directory is a fresh temp dir cleaned on close (an ephemeral dev run); otherwise
     * it persists across restarts (a single-server run).
     */
    static Handle start(Path dataDir, boolean offline) {
        Path binaryJar = resolveBinaryJar(EmbeddedPostgresBinary.classifier(), offline);
        try {
            EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                    .setPgBinaryResolver((system, hardware) -> openBinary(binaryJar));
            if (dataDir != null) {
                builder.setDataDirectory(dataDir).setCleanDataDirectory(false);
            }
            EmbeddedPostgres postgres = builder.start();
            DataSources.MainDatasourceOverride override = new DataSources.MainDatasourceOverride(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
            return new Handle(override, postgres);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to start embedded PostgreSQL", ex);
        }
    }

    private static Path resolveBinaryJar(String classifier, boolean offline) {
        String coordinate = BINARIES_GROUP + ":embedded-postgres-binaries-" + classifier + ":"
                + binariesVersion();
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
