package io.tesseraql.cli;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reports where an {@code --embedded-db} data directory stands relative to the CLI's default
 * PostgreSQL version, and — when the directory is on an older major — prints the safe upgrade
 * procedure. Because this build ships server-only PostgreSQL binaries (no {@code pg_dump}/
 * {@code pg_upgrade}), a cross-major upgrade is driven with the operator's own client tools; this
 * class produces the guidance rather than performing the migration. Pure logic, unit-tested apart
 * from the CLI's I/O.
 */
final class EmbeddedDbStatus {

    enum State {
        /** No cluster on disk yet; the first serve creates it at the default version. */
        UNINITIALIZED,
        /** On disk and pinned to the same major as the default — serve runs it as-is. */
        UP_TO_DATE,
        /** On an older major than the default — an upgrade is available via the operator's tools. */
        UPGRADE_AVAILABLE
    }

    private EmbeddedDbStatus() {
    }

    record Report(Path dataDir, boolean initialized, Optional<String> onDiskMajor,
            Optional<String> pinnedVersion, boolean legacyUnpinned, String defaultVersion,
            State state) {

        String render() {
            String defaultMajor = EmbeddedPostgresDataDir.majorOf(defaultVersion);
            StringBuilder out = new StringBuilder();
            out.append("Embedded PostgreSQL data directory: ").append(dataDir).append('\n');
            if (state == State.UNINITIALIZED) {
                out.append("  Status: not initialized — the first `serve --embedded-db ")
                        .append(dataDir).append("` creates it at PostgreSQL ")
                        .append(defaultVersion)
                        .append(" (major ").append(defaultMajor).append(").");
                return out.toString();
            }
            String onDisk = onDiskMajor.orElse("?");
            out.append("  On-disk PostgreSQL major: ").append(onDisk).append('\n');
            out.append("  Pinned binary version:    ")
                    .append(pinnedVersion.orElse("none (legacy — pinned on next serve)"))
                    .append('\n');
            out.append("  CLI default version:      ").append(defaultVersion).append(" (major ")
                    .append(defaultMajor).append(")\n");
            if (state == State.UP_TO_DATE) {
                out.append("  Status: up to date — `serve --embedded-db ").append(dataDir)
                        .append("` runs PostgreSQL ").append(pinnedVersion.orElse(defaultVersion))
                        .append('.');
                if (legacyUnpinned) {
                    out.append("\n  Note: this directory predates version pinning; the next serve "
                            + "records its version.");
                }
                return out.toString();
            }
            out.append("  Status: an upgrade is available — this directory is PostgreSQL ")
                    .append(onDisk).append(", the CLI default is major ").append(defaultMajor)
                    .append(".\n").append(upgradeProcedure(onDisk));
            return out.toString();
        }

        private String upgradeProcedure(String onDisk) {
            String oldVersion = pinnedVersion.orElse(onDisk + ".x  # the release that created it");
            return """
                    A cross-major move needs your own PostgreSQL client tools (this build ships
                    server-only binaries — pg_dumpall/psql are not bundled). Recommended procedure:

                      # 1. Back up the directory (stop any running serve first).
                      cp -a %1$s %1$s.bak

                      # 2. Start the current version on a fixed port; dump from another shell.
                      tesseraql serve --app <app> --embedded-db %1$s \\
                          --embedded-db-version %2$s --embedded-db-port 5433
                      pg_dumpall -h localhost -p 5433 -U postgres > %1$s.dump.sql   # then Ctrl+C

                      # 3. Move the old data aside; start a fresh directory at the new version.
                      mv %1$s %1$s.old
                      tesseraql serve --app <app> --embedded-db %1$s \\
                          --embedded-db-version %3$s --embedded-db-port 5433

                      # 4. Restore, verify, then clean up.
                      psql -h localhost -p 5433 -U postgres -f %1$s.dump.sql        # then Ctrl+C
                      #   once verified: rm -rf %1$s.old %1$s.bak %1$s.dump.sql
                    """
                    .formatted(dataDir, oldVersion, defaultVersion);
        }
    }

    /** Inspects {@code dataDir} against the given default version and classifies it. */
    static Report of(Path dataDir, String defaultVersion) {
        boolean initialized = EmbeddedPostgresDataDir.isInitialized(dataDir);
        Optional<String> onDiskMajor = EmbeddedPostgresDataDir.onDiskMajor(dataDir);
        Optional<String> pinned = EmbeddedPostgresDataDir.pinnedVersion(dataDir);
        String defaultMajor = EmbeddedPostgresDataDir.majorOf(defaultVersion);

        State state;
        if (!initialized) {
            state = State.UNINITIALIZED;
        } else if (onDiskMajor.map(defaultMajor::equals).orElse(false)) {
            state = State.UP_TO_DATE;
        } else {
            state = State.UPGRADE_AVAILABLE;
        }
        boolean legacyUnpinned = initialized && pinned.isEmpty();
        return new Report(dataDir, initialized, onDiskMajor, pinned, legacyUnpinned, defaultVersion,
                state);
    }
}
