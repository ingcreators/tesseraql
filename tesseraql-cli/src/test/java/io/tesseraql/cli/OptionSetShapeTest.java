package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * The option sets' shape guard (docs/cli-surface.md Decision 7). Membership — "this command loads
 * a manifest, so it must mix in {@code --env}" — cannot be tested, because capability is not
 * readable off the command class: {@code lint} never mentions {@code ManifestLoader} and is the
 * command whose missing {@code --env} was closest to a defect. What decayed, and what is
 * checkable, is the shape: a set option declared as a command's own field is how the sets drifted
 * apart one command at a time, so the names belong to the mixins and nothing else may declare
 * them.
 */
class OptionSetShapeTest {

    /** The option names owned by ConfigOptions, CompileOptions and ConnectionOptions. */
    private static final Set<String> SET_OPTIONS = Set.of("--env", "--modules", "--jdbc-url",
            "--username", "--password", "--datasource");

    private static final Set<Class<?>> MIXINS = Set.of(ConfigOptions.class, CompileOptions.class,
            ConnectionOptions.class);

    /**
     * Named exemptions, each a set spelling carrying a different concept on a command that mixes
     * in no connection set: {@code token --password} is the password a person signs in with, not
     * a database credential, and renaming a documented auth flag to satisfy a shape rule would
     * trade a real name for a tidy one. The list is spelled out so a second entry is a review
     * question, not a drift.
     */
    private static final Set<String> EXEMPT = Set.of("io.tesseraql.cli.TokenCommand.password");

    @Test
    void noCommandDeclaresASetOptionAsItsOwnField() {
        List<String> violations = new ArrayList<>();
        walk(new CommandLine(new TesseraqlCli()), violations);
        assertThat(violations)
                .as("set options belong to the @Mixin classes; a command that needs one mixes"
                        + " the whole set in")
                .isEmpty();
    }

    private static void walk(CommandLine command, List<String> violations) {
        Object userObject = command.getCommandSpec().userObject();
        if (userObject != null && !MIXINS.contains(userObject.getClass())) {
            for (Field field : userObject.getClass().getDeclaredFields()) {
                Option option = field.getAnnotation(Option.class);
                if (option == null) {
                    continue;
                }
                for (String name : option.names()) {
                    if (SET_OPTIONS.contains(name)
                            && !EXEMPT.contains(
                                    userObject.getClass().getName() + "." + field.getName())) {
                        violations.add(userObject.getClass().getName() + "." + field.getName()
                                + " declares " + name);
                    }
                }
            }
        }
        command.getSubcommands().values().forEach(sub -> walk(sub, violations));
    }

    /**
     * Wiring smoke, not a membership rule: the three sets arrived where the mapping in
     * docs/cli-surface.md put them. A handful of representatives, so a refactor that drops a
     * mixin is caught without freezing every command's option list here.
     */
    @Test
    void theSetsAreMixedInWhereTheMappingPutThem() {
        CommandLine root = new CommandLine(new TesseraqlCli());
        assertThat(optionNames(root, "lint")).contains("--env", "--modules");
        assertThat(optionNames(root, "test"))
                .contains("--env", "--modules", "--jdbc-url", "--username", "--password",
                        "--datasource");
        assertThat(optionNames(root, "migrate"))
                .contains("--env", "--jdbc-url", "--datasource");
        assertThat(optionNames(root, "schema")).contains("--env", "--datasource");
        assertThat(optionNames(root, "routes")).contains("--env", "--modules");
        assertThat(optionNames(root, "mcp")).contains("--env", "--modules");
        assertThat(optionNames(root, "dev")).contains("--env", "--modules");
        assertThat(optionNames(root, "host")).contains("--env");
        assertThat(optionNames(root, "package")).contains("--env");
        assertThat(optionNames(root, "token")).contains("--env");
    }

    private static List<String> optionNames(CommandLine root, String command) {
        CommandLine sub = root.getSubcommands().get(command);
        List<String> names = new ArrayList<>();
        sub.getCommandSpec().options()
                .forEach(option -> names.addAll(List.of(option.names())));
        return names;
    }
}
