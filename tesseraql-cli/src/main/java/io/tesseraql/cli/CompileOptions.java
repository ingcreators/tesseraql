package io.tesseraql.cli;

import java.io.File;
import picocli.CommandLine.Option;

/**
 * The compilation option set (docs/cli-surface.md Decision 5): {@code --modules}, mixed into
 * every command that compiles routes or loads module-provided drivers — custom expression
 * functions and JDBC drivers arrive as module jars, so a command that parses expressions without
 * them lints call sites as unknown functions. The directory composes with each application's
 * declared {@code tesseraql.modules} through {@link CliModules}. Membership stays a review
 * question (Decision 7); the option's name belongs to this mixin, guarded by shape.
 */
public final class CompileOptions {

    @Option(names = {"--modules"}, paramLabel = "<dir>", description = "Directory of optional"
            + " plugin module jars (e.g. the pdf/excel file-format codecs), composed with the"
            + " application's declared tesseraql.modules.")
    public File modules;
}
