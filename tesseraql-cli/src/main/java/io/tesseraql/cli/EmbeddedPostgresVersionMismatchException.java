package io.tesseraql.cli;

import java.nio.file.Path;

/**
 * Thrown before starting the embedded server when the version resolved for a {@code --embedded-db}
 * run cannot open the data directory: the directory was initialized by a different PostgreSQL major,
 * whose on-disk format is incompatible. Raised up front (instead of letting {@code postgres} fail
 * cryptically) so the CLI can print an actionable message.
 */
final class EmbeddedPostgresVersionMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    EmbeddedPostgresVersionMismatchException(Path dataDir, String onDiskMajor,
            String requestedVersion) {
        super(message(dataDir, onDiskMajor, requestedVersion));
    }

    private static String message(Path dataDir, String onDiskMajor, String requestedVersion) {
        String requestedMajor = EmbeddedPostgresDataDir.majorOf(requestedVersion);
        return "The embedded data directory " + dataDir + " was initialized by PostgreSQL "
                + onDiskMajor + ", but this run resolved PostgreSQL " + requestedVersion
                + " (major "
                + requestedMajor
                + "). PostgreSQL cannot open a data directory across major versions."
                + System.lineSeparator()
                + "  - To keep the existing data, pin a PostgreSQL " + onDiskMajor
                + " release: --embedded-db-version " + onDiskMajor + ".<minor>."
                + System.lineSeparator()
                + "  - To start over, point --embedded-db at a new empty directory.";
    }
}
