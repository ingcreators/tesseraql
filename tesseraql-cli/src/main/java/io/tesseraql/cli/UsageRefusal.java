package io.tesseraql.cli;

/**
 * A request the CLI cannot run at all: a flag that is missing or does not parse, a declaration
 * the command cannot act on, an input file that is not there. Nothing has been done when it is
 * thrown, and it must be thrown before anything is — a refusal after the work is a failure,
 * not a refusal.
 *
 * <p>{@link CliExceptionHandler} prints the message as one line on stderr and exits
 * {@code 2}, the code every hand-written {@code System.err.println(…); return 2;} refusal in
 * this CLI already uses and {@code docs/jobs.md} publishes for "a request that cannot run at
 * all" (docs/cli-surface.md decision 10). It extends {@link IllegalArgumentException} because
 * that is what it is; the handler shapes only this subclass, so a genuine
 * {@code IllegalArgumentException} from deeper code keeps its stack trace and exit 1.
 */
public final class UsageRefusal extends IllegalArgumentException {

    public UsageRefusal(String message) {
        super(message);
    }
}
