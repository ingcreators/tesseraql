package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The README's hand-typed surface facts agree with the sources they describe
 * (docs/audit-medium-leads.md slice 10, F82). The README is the GitHub landing page; it told
 * that reader a Studio URL that 404s, a recipe list three recipes short of the surface, and —
 * before #1257 — a module layout the build forbids and a CLI list a third smaller than the
 * real one. Each list that stays hand-typed is compared here with what generates the truth.
 */
class ReadmeLedgerTest {

    private static final Path REPO = Path.of("..");

    @Test
    void theRecipeListIsTheYamlSurfacesEnum() throws IOException {
        String readme = Files.readString(REPO.resolve("README.md"), StandardCharsets.UTF_8);
        String bullet = paragraph(readme, "**Declarative routes**");
        TreeSet<String> listed = backticked(bullet);

        String surface = Files.readString(REPO.resolve("docs/reference-yaml-surface.md"),
                StandardCharsets.UTF_8);
        // The enum cell escapes its pipes (`a` \| `b`), so the row is cut at its description
        // cell rather than at a pipe.
        String row = surface.lines().filter(line -> line.startsWith("| `recipe` | enum: "))
                .findFirst().orElseThrow();
        String enumCell = row.substring("| `recipe` | enum: ".length(), row.indexOf(" | What"));
        TreeSet<String> surfaced = backticked(enumCell);

        assertThat(listed).as("the README's recipe list against the generated surface")
                .isEqualTo(surfaced);
    }

    @Test
    void theMavenGoalListIsThePluginsMojos() throws IOException {
        String readme = Files.readString(REPO.resolve("README.md"), StandardCharsets.UTF_8);
        String row = readme.lines()
                .filter(line -> line.startsWith("| `tesseraql-maven-plugin` |"))
                .findFirst().orElseThrow();
        TreeSet<String> listed = backticked(row.substring("| `tesseraql-maven-plugin` |".length()));

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
        assertThat(mojos).isNotEmpty();
        assertThat(listed).as("the README's Maven goal list against the plugin's mojos")
                .isEqualTo(mojos);
    }

    @Test
    void theStudioUrlIsTheStacksNotTheApplications() throws IOException {
        String readme = Files.readString(REPO.resolve("README.md"), StandardCharsets.UTF_8);
        // Studio is served at the stack's origin (docs/studio.md); a per-application prefix
        // answers 404.
        assertThat(readme).doesNotContain("/<name>/_tesseraql/studio")
                .contains("Studio at /_tesseraql/studio");
    }

    private static String paragraph(String markdown, String opening) {
        int start = markdown.indexOf(opening);
        assertThat(start).as(opening).isNotNegative();
        int end = markdown.indexOf("\n- ", start + 1);
        return markdown.substring(start, end < 0 ? markdown.length() : end);
    }

    private static TreeSet<String> backticked(String text) {
        TreeSet<String> names = new TreeSet<>();
        Matcher code = Pattern.compile("`([a-z][a-z-]*)`").matcher(text);
        while (code.find()) {
            names.add(code.group(1));
        }
        return names;
    }
}
