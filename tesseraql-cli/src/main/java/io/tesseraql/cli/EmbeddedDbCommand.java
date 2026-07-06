package io.tesseraql.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql embedded-db info <data-dir>}: reports the PostgreSQL version an
 * {@code --embedded-db} data directory is on, whether it matches the CLI's default, and — when the
 * directory is on an older major — the safe upgrade procedure. Inspection only; it never starts a
 * server or touches the directory. See {@link EmbeddedDbStatus} for the classification logic.
 */
@Command(name = "embedded-db", description = "Inspect an --embedded-db data directory and its PostgreSQL version.", subcommands = {
        EmbeddedDbCommand.InfoCommand.class})
final class EmbeddedDbCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // No subcommand given: default to info would need a data dir, so show usage instead.
        System.err.println("Usage: tesseraql embedded-db info <data-dir>");
        return 2;
    }

    /** {@code tesseraql embedded-db info <data-dir>}. */
    @Command(name = "info", description = "Report the directory's PostgreSQL version and upgrade guidance.")
    static final class InfoCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<data-dir>", description = "The --embedded-db data directory to inspect.")
        Path dataDir;

        @Override
        public Integer call() {
            System.out.println(
                    EmbeddedDbStatus.of(dataDir, EmbeddedPostgresSupport.defaultVersion())
                            .render());
            return 0;
        }
    }
}
