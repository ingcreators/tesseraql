package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The notes on an extension release are the extension's pull requests, and only those.
 *
 * <p>{@code extension-release.yml} used {@code gh release create --generate-notes}. The extension
 * shares the repository, the commit line and the release list with the framework, and GitHub's
 * automatic notes range from whichever release they take for the previous one: ext-v0.3.13 was
 * ranged from v0.14.0, ext-v0.3.16 from v0.16.0 — reproducing v0.17.0's seventy-two lines under
 * the extension's title, published minutes apart on the same commit. Ranging from the previous
 * {@code ext-v*} tag would not have fixed it: ext-v0.3.15..ext-v0.3.16 holds 206 pull requests,
 * five of which touched {@code vscode-extension/}.
 *
 * <p>{@code .github/scripts/extension-release-notes.sh} filters by that path, and this drives the
 * shipped script over a repository built here: framework and extension pull requests interleaved
 * across two extension tags. Verified red against a variant of the script with the path filter
 * removed, which listed the framework's pull requests too, and — with the path filter in place
 * but the previous tag ignored — against the tag-before-last's extension pull request.
 */
class ExtensionReleaseNotesScriptTest {

    private static final Path SCRIPT = Path
            .of("..", ".github", "scripts", "extension-release-notes.sh").toAbsolutePath()
            .normalize();

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theNotesListThePullRequestsThatTouchedTheExtensionSinceItsPreviousTag(@TempDir Path repo)
            throws Exception {
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "ledger@example.invalid");
        git(repo, "config", "user.name", "ledger");
        git(repo, "config", "commit.gpgsign", "false");

        commit(repo, "tesseraql-core/A.java", "feat(core): a framework thing (#1)");
        commit(repo, "vscode-extension/src/a.ts", "feat(ext): the first extension thing (#2)");
        git(repo, "tag", "ext-v0.1.0");
        commit(repo, "tesseraql-runtime/B.java", "fix(runtime): framework only (#3)");
        commit(repo, "vscode-extension/src/b.ts", "fix(ext): the tooltip names the verb (#4)");
        commit(repo, "vscode-extension/pnpm-lock.yaml",
                "build(deps-dev): bump typescript in /vscode-extension (#5)");
        commit(repo, "docs/x.md", "docs: unrelated to the extension (#6)");
        commit(repo, "vscode-extension/README.md", "a merge commit with no pull request number");
        git(repo, "tag", "ext-v0.1.1");
        commit(repo, "vscode-extension/src/c.ts", "feat(ext): after the tag (#7)");

        String notes = notes(repo, "ext-v0.1.1");

        assertThat(notes)
                .as("the extension's pull requests since ext-v0.1.0, oldest first, each linked")
                .contains("## What's Changed\n"
                        + "* fix(ext): the tooltip names the verb in"
                        + " https://github.com/ingcreators/tesseraql/pull/4\n"
                        + "* build(deps-dev): bump typescript in /vscode-extension in"
                        + " https://github.com/ingcreators/tesseraql/pull/5\n"
                        + "* a merge commit with no pull request number\n")
                .contains("since ext-v0.1.0.")
                .contains("**Full Changelog**:"
                        + " https://github.com/ingcreators/tesseraql/commits/ext-v0.1.1/vscode-extension");
        assertThat(notes)
                .as("no framework pull request, nothing before the previous tag, nothing after this one")
                .doesNotContain("pull/1", "pull/2", "pull/3", "pull/6", "pull/7");
    }

    /**
     * The first extension release has no previous tag and ranges over the whole history — still
     * filtered by the path, or the first release would carry every framework pull request ever.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theFirstReleaseRangesOverTheWholeHistoryUnderThePath(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "ledger@example.invalid");
        git(repo, "config", "user.name", "ledger");
        git(repo, "config", "commit.gpgsign", "false");

        commit(repo, "tesseraql-core/A.java", "feat(core): a framework thing (#1)");
        commit(repo, "vscode-extension/src/a.ts", "feat(ext): the first extension thing (#2)");
        git(repo, "tag", "ext-v0.1.0");

        String notes = notes(repo, "ext-v0.1.0");

        assertThat(notes).contains("pull/2").doesNotContain("pull/1").doesNotContain("since ");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aTagOutsideTheExtensionNamespaceIsRefused(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "ledger@example.invalid");
        git(repo, "config", "user.name", "ledger");
        git(repo, "config", "commit.gpgsign", "false");
        commit(repo, "pom.xml", "release: 0.17.0");
        git(repo, "tag", "v0.17.0");

        Result result = run(repo, List.of("bash", SCRIPT.toString(), "v0.17.0"));

        assertThat(result.exit()).as("a framework tag is not an extension release").isEqualTo(1);
        assertThat(result.output()).contains("not an ext-v* tag");
    }

    private static String notes(Path repo, String tag) throws Exception {
        Result result = run(repo, List.of("bash", SCRIPT.toString(), tag));
        assertThat(result.exit()).as("the script's exit for %s:%n%s", tag, result.output())
                .isZero();
        return result.output();
    }

    private static void commit(Path repo, String file, String subject) throws Exception {
        Path target = repo.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, subject + "\n", StandardCharsets.UTF_8);
        git(repo, "add", file);
        git(repo, "commit", "-q", "-m", subject);
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Result result = run(repo, command);
        assertThat(result.exit()).as("git %s:%n%s", String.join(" ", args), result.output())
                .isZero();
    }

    private static Result run(Path directory, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true);
        // A tag on the temp repository must not inherit this checkout's identity or hooks.
        builder.environment().remove("GIT_DIR");
        builder.environment().remove("GIT_WORK_TREE");
        builder.environment().remove("GITHUB_REPOSITORY");
        Process process = builder.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out: " + command);
        }
        return new Result(process.exitValue(), output);
    }

    private record Result(int exit, String output) {
    }
}
