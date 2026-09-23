package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The files a contributor sets the repository up from explain themselves in this codebase's
 * terms (docs/audit-low-leads.md slice 23, F95). Two of them kept reasoning in Camel's — the
 * IDE's null-analysis rationale in {@code .vscode/settings.json} and the {@code resource}
 * suppression example in {@code docs/build.md} — a month after the Camel removal, and the first
 * was wrong at birth about which library carried the annotations. Design records and the
 * CHANGELOG say what was true when they were written; living guidance says what is true now.
 */
class ContributorSetupLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final List<String> SETUP_FILES = List.of(".vscode/settings.json",
            ".devcontainer/devcontainer.json", "mise.toml", "docs/build.md", "CONTRIBUTING.md",
            "AGENTS.md", "docs/development-environment.md");

    @Test
    void noContributorSetupFileReasonsInCamelsTerms() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String relative : SETUP_FILES) {
            Path file = REPO.resolve(relative);
            assertThat(file).as(relative).isRegularFile();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("Camel")) {
                    offenders.add(relative + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertThat(offenders).as("contributor-setup lines that reason in Camel's terms")
                .isEmpty();
    }
}
