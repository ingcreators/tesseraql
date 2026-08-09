package io.tesseraql.docs;

import io.tesseraql.cli.TesseraqlCli;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * The CLI reference (docs/documentation-ia.md): a walk of the Picocli command model —
 * the same model {@code tesseraql --help} prints from — rendered as one markdown page.
 *
 * <p>Twenty-seven subcommands were scattered through the prose of fifty pages with no
 * page listing them, on a framework whose primary interface is the CLI. Generating it
 * from the command model means the page cannot describe a flag the binary does not have.
 */
final class CliReference {

    private CliReference() {
    }

    /** Renders the whole page from the root command's model. */
    static String render() {
        CommandSpec root = new CommandLine(new TesseraqlCli()).getCommandSpec();
        StringBuilder md = new StringBuilder();
        md.append("# CLI reference\n\n")
                .append("Every `tesseraql` subcommand, generated from the command model the "
                        + "binary itself parses with, so it cannot describe a flag that does "
                        + "not exist. `tesseraql <command> --help` prints the same content at "
                        + "the terminal.\n\n")
                .append("Most commands take `--app <dir>`, the application home they act on. "
                        + "Every subcommand calls the same engine as the matching Maven goal, "
                        + "so a CLI loop and a CI pipeline do the same work.\n");

        List<CommandSpec> commands = subcommands(root);
        List<String> toc = new ArrayList<>();
        for (CommandSpec command : commands) {
            toc.add("[`" + command.name() + "`](#" + ReferenceGenerator.slug(command.name()) + ")");
        }
        md.append('\n').append(String.join(" · ", toc)).append('\n');

        for (CommandSpec command : commands) {
            renderCommand(md, command, command.name(), 2);
        }
        return md.toString();
    }

    /** A command's own section: its description, its arguments, then its subcommands. */
    private static void renderCommand(StringBuilder md, CommandSpec command, String path,
            int level) {
        md.append('\n').append("#".repeat(Math.min(level, 6))).append(" `").append(path)
                .append("`\n");
        String description = String.join(" ", command.usageMessage().description());
        if (!description.isBlank()) {
            md.append('\n').append(description).append('\n');
        }

        List<ArgSpec> arguments = new ArrayList<>();
        for (PositionalParamSpec positional : command.positionalParameters()) {
            if (!positional.hidden()) {
                arguments.add(positional);
            }
        }
        for (OptionSpec option : command.options()) {
            // --help and --version are on every command; listing them 27 times is noise.
            if (!option.hidden() && !option.usageHelp() && !option.versionHelp()) {
                arguments.add(option);
            }
        }
        if (!arguments.isEmpty()) {
            md.append("\n| Argument | Required? | Description |\n| --- | --- | --- |\n");
            for (ArgSpec argument : arguments) {
                // A param label like <text|json> would otherwise close the table cell.
                md.append("| `").append(names(argument).replace("|", "\\|")).append('`')
                        .append(" | ").append(argument.required() ? "yes" : "—")
                        .append(" | ").append(cellFor(argument)).append(" |\n");
            }
        }

        for (CommandSpec child : subcommands(command)) {
            renderCommand(md, child, path + " " + child.name(), level + 1);
        }
    }

    /** Declared subcommands in declaration order, minus Picocli's generated help command. */
    private static List<CommandSpec> subcommands(CommandSpec command) {
        List<CommandSpec> commands = new ArrayList<>();
        for (CommandLine line : command.subcommands().values()) {
            CommandSpec spec = line.getCommandSpec();
            if (!spec.usageMessage().hidden() && !"help".equals(spec.name())
                    && !commands.contains(spec)) {
                commands.add(spec);
            }
        }
        return commands;
    }

    /** The argument as it is typed: an option's names, or a positional's parameter label. */
    private static String names(ArgSpec argument) {
        if (argument instanceof OptionSpec option) {
            String rendered = String.join(", ", option.names());
            return option.arity().max() > 0
                    ? rendered + " " + option.paramLabel()
                    : rendered;
        }
        return argument.paramLabel();
    }

    /** The description cell, with the default value appended where the model carries one. */
    private static String cellFor(ArgSpec argument) {
        String text = String.join(" ", argument.description());
        String defaultValue = argument.defaultValue();
        if (defaultValue != null && !defaultValue.isBlank() && !argument.required()
                && !"null".equals(defaultValue) && !"false".equals(defaultValue)) {
            text = text.isBlank()
                    ? "Default: `" + defaultValue + "`."
                    : text.trim() + (text.trim().endsWith(".") ? "" : ".")
                            + " Default: `" + defaultValue + "`.";
        }
        return text.isBlank() ? "—" : ReferenceGenerator.cell(text);
    }
}
