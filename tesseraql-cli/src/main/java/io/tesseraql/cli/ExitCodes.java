package io.tesseraql.cli;

/**
 * The exit codes every {@code tesseraql} and {@code tesseraql-host} command answers with
 * (docs/cli-surface.md decision 10), declared once as picocli {@code exitCodeList} entries so
 * both root commands print the same list under {@code --help} and the generated CLI reference
 * renders it from the model. The rule: {@code 2} means nothing ran — the request could not be
 * run at all — and {@code 1} means the command ran and failed, so a script can tell a mistyped
 * invocation from a broken bootstrap by the number alone.
 */
final class ExitCodes {

    static final String OK = "0:The command did what was asked.";

    static final String FAILED = "1:The command ran and failed — a database it could not reach"
            + " (one operator message, no stack trace), a job that ended FAILED, or an"
            + " unexpected error with its stack trace.";

    static final String REFUSED = "2:Nothing ran: the request could not be run at all — a"
            + " missing or unparseable flag, a directory that is not an application, a"
            + " declaration the command cannot act on, a modules.lock that does not match — one"
            + " line on stderr saying what to change, or the framework's own coded sentence"
            + " (TQL-…) naming the declaration.";

    static final String SKIPPED = "3:`job run` only: the job did not run by policy — its"
            + " business-day calendar filtered the date out, or the overlap policy skipped the"
            + " firing.";

    private ExitCodes() {
    }
}
