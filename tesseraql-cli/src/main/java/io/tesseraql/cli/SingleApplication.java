package io.tesseraql.cli;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.AppDirectory;
import java.nio.file.Path;

/**
 * The single-application commands' {@code --app} resolution (docs/cli-surface.md Decision 2):
 * {@code package}, {@code scaffold}, {@code release-diff} and {@code verify} operate on exactly
 * one application, so a directory holding several is refused with the commands that would have
 * worked — a refusal that names the alternatives costs a second, one that says "expected a single
 * application" costs a directory listing and a guess.
 */
final class SingleApplication {

    private SingleApplication() {
    }

    /**
     * The one application {@code dir} holds, absolute and normalized — or {@code null} after
     * printing the refusal to stderr, which the caller answers with a non-zero exit.
     */
    static Path resolve(Path dir, String commandForHelp) {
        return resolve(dir, commandForHelp, "--app");
    }

    /** Like {@link #resolve(Path, String)} for a flag not spelled {@code --app}. */
    static Path resolve(Path dir, String commandForHelp, String flag) {
        try {
            return AppDirectory.application(dir, commandForHelp, flag);
        } catch (TqlException refused) {
            System.err.println(refused.getMessage());
            return null;
        }
    }
}
