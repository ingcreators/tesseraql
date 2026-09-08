package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.tesseraql.cli.TesseraqlCli;
import io.tesseraql.cli.TesseraqlHostCli;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The CLI reference's deployment roster names exactly the verbs {@code tesseraql-host} declares.
 *
 * <p><b>Why this is scoped to the section rather than to the page.</b> The generated table of
 * contents already renders every host verb with a working anchor — {@code [`host`](#host)} and the
 * other nine — and each already has its own {@code ## } section, because every host verb is one of
 * the developer CLI's own command classes. So <em>any</em> whole-page assertion about those names
 * or anchors passes on a page with no roster at all. The redness lives entirely in the section
 * existing, which is why the extraction below fails loudly instead of falling back to the whole
 * page: a {@code Math.max(0, indexOf)} or an {@code orElse(page)} would silently re-point every
 * assertion at the table of contents and pass.
 *
 * <p>The expected names are derived from the command model on both sides rather than written down
 * here. {@code TesseraqlHostCliTest} is the one place that pins the roster as a literal, and it
 * stays the only one — two hand-maintained copies of a roster is the defect this campaign exists
 * to close.
 */
class CliReferenceHostRosterTest {

    private static final Pattern ROSTER_HEADING = Pattern.compile("^## The deployment roster$",
            Pattern.MULTILINE);
    private static final Pattern NEXT_HEADING = Pattern.compile("^## ", Pattern.MULTILINE);
    private static final Pattern ROW = Pattern
            .compile("^\\| \\[`([^`]+)`\\]\\(#([^)]+)\\) \\| (.+) \\|$", Pattern.MULTILINE);
    private static final Pattern HEADING = Pattern.compile("^#{2,6} (.+)$", Pattern.MULTILINE);

    @Test
    void theRosterNamesExactlyTheHostVerbsAndLinksToTheirSections() {
        String page = ReferenceGenerator.cli();
        CommandSpec host = new CommandLine(new TesseraqlHostCli()).getCommandSpec();
        List<String> expected = verbs(host);
        assertThat(expected).as("tesseraql-host declares verbs to publish").isNotEmpty();

        String section = rosterSection(page);
        List<String> names = new ArrayList<>();
        Matcher row = ROW.matcher(section);
        while (row.find()) {
            String name = row.group(1);
            names.add(name);
            assertThat(row.group(2)).as("the '%s' row links to its own section's anchor", name)
                    .isEqualTo(ReferenceGenerator.slug(name));
            assertThat(row.group(3)).as("the '%s' row describes the verb", name).isNotBlank();
        }

        // The row COUNT is the anti-vacuity assertion. The section's prose is always present, so
        // asserting the section is non-blank would pass on a table that rendered no rows at all.
        assertThat(names).as("the deployment roster's rows").isNotEmpty();
        assertThat(names).as("the roster names exactly what tesseraql-host declares")
                .containsExactlyInAnyOrderElementsOf(expected);

        Set<String> anchors = new LinkedHashSet<>();
        Matcher heading = HEADING.matcher(page);
        while (heading.find()) {
            anchors.add(ReferenceGenerator.slug(heading.group(1).replace("`", "")));
        }
        for (String name : names) {
            assertThat(anchors).as("the '%s' row's anchor resolves to a heading on this page", name)
                    .contains(ReferenceGenerator.slug(name));
        }
    }

    /**
     * Green today and after, deliberately: it is the precondition that lets the roster link into
     * this page instead of re-rendering ten sections. If a host verb ever stops being a developer
     * verb, the roster's anchors start dangling and this says so before a reader finds out.
     */
    @Test
    void everyHostVerbIsADeveloperVerbSoTheRosterCanLinkIntoThisPage() {
        List<String> host = verbs(new CommandLine(new TesseraqlHostCli()).getCommandSpec());
        List<String> developer = verbs(new CommandLine(new TesseraqlCli()).getCommandSpec());

        assertThat(host).isNotEmpty();
        assertThat(developer).isNotEmpty();
        assertThat(developer).as("every deployment verb is a developer verb").containsAll(host);
    }

    /** The roster section, or a failure naming the heading that is missing. Never a fallback. */
    private static String rosterSection(String page) {
        Matcher start = ROSTER_HEADING.matcher(page);
        if (!start.find()) {
            fail("ReferenceGenerator.cli() has no '## The deployment roster' heading, so the"
                    + " deployment roster is not generated. Every host verb already appears in"
                    + " this page's table of contents, so a whole-page assertion cannot see"
                    + " this.");
        }
        Matcher next = NEXT_HEADING.matcher(page);
        int end = next.find(start.end()) ? next.start() : page.length();
        return page.substring(start.end(), end);
    }

    /** Declared verbs, filtered exactly as {@code CliReference} filters them for the page. */
    private static List<String> verbs(CommandSpec command) {
        List<String> names = new ArrayList<>();
        for (CommandLine line : command.subcommands().values()) {
            CommandSpec spec = line.getCommandSpec();
            if (!spec.usageMessage().hidden() && !"help".equals(spec.name())
                    && !names.contains(spec.name())) {
                names.add(spec.name());
            }
        }
        return names;
    }
}
