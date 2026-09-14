package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The VS Code extension's tests are where its test runner looks, and the command names its
 * listing and its page advertise are the ones the manifest declares
 * (docs/audit-medium-leads.md slice 10, F87 and F91).
 *
 * <p>{@code emailFragments.test.ts} lived under {@code src/test/}, compiled to
 * {@code out/src/test/}, and the runner's glob is {@code out/test/*.test.js}: its four assertions
 * never ran in any CI job, and every {@code .vsix} since 0.3.8 shipped the unused module. The
 * Marketplace README and {@code docs/vscode-extension.md} advertised *TesseraQL: Serve* and
 * *TesseraQL: Open Server* two releases after the rename to *Dev* and *Open Served App*: a user
 * searched the palette for a command that does not exist.
 */
class ExtensionLedgerTest {

    private static final Path EXTENSION = Path.of("..", "vscode-extension");

    private static final Pattern MENTION = Pattern.compile("\\*+TesseraQL: ([^*`\\n]+?)\\*+");

    @Test
    void everyTestFileIsWhereTheRunnerLooks() throws IOException {
        String runner = new ObjectMapper().readTree(EXTENSION.resolve("package.json").toFile())
                .path("scripts").path("test").asText();
        assertThat(runner).as("the test script").contains("out/test/*.test.js");

        List<String> misplaced = new ArrayList<>();
        try (Stream<Path> files = Files.walk(EXTENSION)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String rel = EXTENSION.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("node_modules/") || rel.startsWith("out/")
                        || !rel.endsWith(".test.ts")) {
                    continue;
                }
                // The glob is one level deep under test/: anything else compiles somewhere
                // the runner never reads.
                if (!rel.matches("test/[^/]+\\.test\\.ts")) {
                    misplaced.add(rel);
                }
            }
        }
        assertThat(misplaced).as("test files outside test/, which the runner never executes")
                .isEmpty();
    }

    @Test
    void everyAdvertisedCommandNameIsOneTheManifestDeclares() throws IOException {
        JsonNode manifest = new ObjectMapper().readTree(EXTENSION.resolve("package.json").toFile());
        TreeSet<String> titles = new TreeSet<>();
        manifest.path("contributes").path("commands")
                .forEach(command -> titles.add(command.path("title").asText()));
        assertThat(titles).as("declared command titles").isNotEmpty();

        List<String> unknown = new ArrayList<>();
        for (Path page : List.of(EXTENSION.resolve("README.md"),
                Path.of("..", "docs", "vscode-extension.md"))) {
            String text = Files.readString(page, StandardCharsets.UTF_8);
            Matcher mentions = MENTION.matcher(text);
            while (mentions.find()) {
                // A slash-separated run names several commands at once.
                for (String name : mentions.group(1).split(" / ")) {
                    if (!titles.contains(name.strip())) {
                        unknown.add(page.getFileName() + ": TesseraQL: " + name.strip());
                    }
                }
            }
        }
        assertThat(unknown).as("advertised command names the manifest does not declare")
                .isEmpty();
    }
}
