package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.cli.TesseraqlCli;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The CLI reference's parity sentence names exactly the subcommands that share an engine with a
 * {@code tesseraql:} Maven goal (docs/audit-low-leads.md slice 23, F116). The page used to say
 * "every subcommand calls the same engine as the matching Maven goal" — false for thirteen of
 * twenty-five subcommands — and the generator's drift guard could only pin the page to the
 * generator, never judge the sentence. The parity on record runs the other way
 * (docs/app-developer-distribution.md: every goal whose engine is a reusable library is a
 * subcommand), so the list is derived here from the plugin's {@code @Mojo} names, the way
 * {@link ReadmeLedgerTest} reads them, and compared with what the generator spells.
 */
class CliMavenParityLedgerTest {

    private static final Path REPO = Path.of("..");

    @Test
    void theParityListIsThePluginsGoalsMappedToTheirVerbs() throws IOException {
        TreeSet<String> derived = new TreeSet<>();
        for (String goal : mojoNames()) {
            if (CliReference.CI_ONLY_GOALS.contains(goal)) {
                continue;
            }
            derived.add(CliReference.GOAL_VERBS.getOrDefault(goal, goal));
        }
        assertThat(new TreeSet<>(CliReference.MAVEN_GOAL_VERBS))
                .as("the verbs the page names against the plugin's goals")
                .isEqualTo(derived);
        // A CI-only goal is one the plugin declares; a goal the mapping renames exists too.
        assertThat(mojoNames()).containsAll(CliReference.CI_ONLY_GOALS)
                .containsAll(CliReference.GOAL_VERBS.keySet());
    }

    @Test
    void everyVerbThePageNamesIsASubcommandAndTheSentenceSpellsThemAll() {
        TreeSet<String> subcommands = new TreeSet<>(
                new CommandLine(new TesseraqlCli()).getCommandSpec().subcommands().keySet());
        assertThat(subcommands).containsAll(CliReference.MAVEN_GOAL_VERBS);
        String sentence = CliReference.parityParagraph();
        for (String verb : CliReference.MAVEN_GOAL_VERBS) {
            assertThat(sentence).contains("`" + verb + "`");
        }
        assertThat(sentence).doesNotContain("Every subcommand calls the same engine");
    }

    private static TreeSet<String> mojoNames() throws IOException {
        TreeSet<String> mojos = new TreeSet<>();
        Pattern name = Pattern.compile("@Mojo\\(name = \"([a-z-]+)\"");
        try (Stream<Path> files = Files
                .walk(REPO.resolve("tesseraql-maven-plugin/src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher mojo = name.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (mojo.find()) {
                    mojos.add(mojo.group(1));
                }
            }
        }
        assertThat(mojos).as("the plugin declares goals").isNotEmpty();
        return mojos;
    }
}
