package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Marketplace token check survives a request that timed out, and only that.
 *
 * <p>{@code extension-release.yml} ran {@code vsce verify-pat} bare. On ext-v0.3.16 the request
 * behind it timed out twice — {@code Request timeout: /_apis/securityroles}, three minutes each,
 * seven minutes apart — and answered in two seconds on the third re-run. Nothing publishes before
 * that step, so each re-run was from the same tag, but each was a person pressing "Re-run failed
 * jobs" on the one path no pull request rehearses. Other publishers hit the identical signature;
 * it is Azure DevOps, not the token: an expired or wrong token is refused at once, with
 * {@code TF400813: The user '…' is not authorized}.
 *
 * <p>{@code .github/scripts/verify-marketplace-token.sh} retries the timeout — three attempts,
 * fifteen seconds apart — and nothing else. This drives the shipped script with a scripted
 * {@code vsce} and a recording {@code sleep} on PATH, so the attempts and the pauses are
 * observed rather than waited for. Verified red on four variants of the script: no retry at
 * all (the third attempt never came), a retry on every failure (a refused token was asked
 * again and the step passed), no pause between attempts, and four attempts instead of three.
 */
class VerifyMarketplaceTokenScriptTest {

    private static final Path SCRIPT = Path
            .of("..", ".github", "scripts", "verify-marketplace-token.sh").toAbsolutePath()
            .normalize();

    /** What vsce prints when the token holds: stdout, exit 0. */
    private static final String HOLDS = "echo \"The Personal Access Token verification succeeded"
            + " for the publisher '$2'.\"; exit 0";

    /**
     * What vsce printed on ext-v0.3.16, in the shape the runner sees: under {@code GITHUB_ACTIONS}
     * vsce writes its failure to stdout as an {@code ::error::} workflow command with the message's
     * newlines escaped, and the runner renders that as the annotation in the log.
     */
    private static final String TIMED_OUT = "echo '::error::The Personal Access Token verification"
            + " has failed. Additional information:%0A%0ARequest timeout: /_apis/securityroles';"
            + " exit 1";

    /** What vsce prints for a token Azure DevOps refuses — immediately, so never worth a retry. */
    private static final String REFUSED = "echo \"::error::The Personal Access Token verification"
            + " has failed. Additional information:%0A%0ATF400813: The user"
            + " 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' is not authorized to access this resource.\";"
            + " exit 1";

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aTimedOutRequestIsRetriedAndTheTokenHoldsOnTheThirdAttempt(@TempDir Path home)
            throws Exception {
        Harness harness = new Harness(home, List.of(TIMED_OUT, TIMED_OUT, HOLDS));

        Result result = harness.verify("ingcreators");

        assertThat(result.exit()).as("the token held on the third attempt:%n%s", result.output())
                .isZero();
        assertThat(harness.calls()).as("vsce asked once per attempt, with the token in its env")
                .containsExactly("verify-pat ingcreators secret-token",
                        "verify-pat ingcreators secret-token",
                        "verify-pat ingcreators secret-token");
        assertThat(harness.sleeps()).as("fifteen seconds between attempts, none after the last")
                .containsExactly("15", "15");
        assertThat(result.output())
                .contains("attempt 1/3: Request timeout: /_apis/securityroles; retrying in 15s")
                .contains("attempt 2/3: Request timeout: /_apis/securityroles; retrying in 15s")
                .contains("verification succeeded for the publisher 'ingcreators'");
        assertThat(result.output())
                .as("a retried attempt's ::error:: is not repeated; the run went on to pass and"
                        + " must not carry an error annotation")
                .doesNotContain("::error::");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aTokenThatHoldsIsAskedOnce(@TempDir Path home) throws Exception {
        Harness harness = new Harness(home, List.of(HOLDS));

        Result result = harness.verify("ingcreators");

        assertThat(result.exit()).isZero();
        assertThat(harness.calls()).containsExactly("verify-pat ingcreators secret-token");
        assertThat(harness.sleeps()).isEmpty();
        assertThat(result.output())
                .contains("verification succeeded for the publisher 'ingcreators'");
    }

    /**
     * A refused token is the failure the dry run exists to catch — the PAT expires — and a retry
     * would only delay the answer by thirty seconds and blur it with a timeout's.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aRefusedTokenIsFinalOnItsFirstAttempt(@TempDir Path home) throws Exception {
        Harness harness = new Harness(home, List.of(REFUSED, HOLDS, HOLDS));

        Result result = harness.verify("ingcreators");

        assertThat(result.exit()).as("not a timeout, so not retried:%n%s", result.output())
                .isEqualTo(1);
        assertThat(harness.calls()).containsExactly("verify-pat ingcreators secret-token");
        assertThat(harness.sleeps()).isEmpty();
        assertThat(result.output())
                .as("vsce's own message, annotation and all, so the log says why")
                .contains("::error::The Personal Access Token verification has failed."
                        + " Additional information:%0A%0ATF400813: The user"
                        + " 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' is not authorized")
                .doesNotContain("retrying");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void threeTimeoutsFailTheStepWithTheLastAttemptsOutput(@TempDir Path home) throws Exception {
        Harness harness = new Harness(home, List.of(TIMED_OUT, TIMED_OUT, TIMED_OUT, HOLDS));

        Result result = harness.verify("ingcreators");

        assertThat(result.exit()).as("three timeouts:%n%s", result.output()).isEqualTo(1);
        assertThat(harness.calls()).as("three attempts, not a fourth")
                .containsExactly("verify-pat ingcreators secret-token",
                        "verify-pat ingcreators secret-token",
                        "verify-pat ingcreators secret-token");
        assertThat(harness.sleeps()).as("no pause after the last attempt")
                .containsExactly("15", "15");
        assertThat(result.output())
                .contains("::error::The Personal Access Token verification has failed."
                        + " Additional information:%0A%0ARequest timeout: /_apis/securityroles")
                .contains("timed out on all 3 attempts")
                .contains("re-run this job from the same tag");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void thePublisherIsRequired(@TempDir Path home) throws Exception {
        Harness harness = new Harness(home, List.of(HOLDS));

        Result result = harness.verify();

        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains("usage: verify-marketplace-token.sh <publisher>");
        assertThat(harness.calls()).isEmpty();
    }

    /**
     * A {@code bin/} ahead of PATH holding a {@code vsce} that answers the n-th call with the n-th
     * scripted outcome and records each call, and a {@code sleep} that records its argument and
     * returns at once. Both are plain files here, so a PATH lookup is the whole substitution.
     */
    private static final class Harness {

        private final Path home;
        private final Path bin;
        private final Path calls;
        private final Path sleeps;

        Harness(Path home, List<String> outcomes) throws IOException {
            this.home = home;
            bin = Files.createDirectory(home.resolve("bin"));
            calls = home.resolve("calls");
            sleeps = home.resolve("sleeps");

            StringBuilder vsce = new StringBuilder("#!/usr/bin/env bash\n")
                    .append("echo \"$1 $2 ${VSCE_PAT:-unset}\" >> '").append(calls).append("'\n")
                    .append("case \"$(wc -l < '").append(calls).append("')\" in\n");
            for (int i = 0; i < outcomes.size(); i++) {
                vsce.append("  ").append(i + 1).append(") ").append(outcomes.get(i))
                        .append(" ;;\n");
            }
            vsce.append("  *) echo 'vsce: asked more often than scripted' >&2; exit 99 ;;\n")
                    .append("esac\n");
            executable(bin.resolve("vsce"), vsce.toString());
            executable(bin.resolve("sleep"),
                    "#!/usr/bin/env bash\necho \"$1\" >> '" + sleeps + "'\n");
        }

        Result verify(String... args) throws Exception {
            List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("PATH", bin + ":" + builder.environment().get("PATH"));
            builder.environment().put("VSCE_PAT", "secret-token");
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

        List<String> calls() throws IOException {
            return lines(calls);
        }

        List<String> sleeps() throws IOException {
            return lines(sleeps);
        }

        private static List<String> lines(Path file) throws IOException {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        }

        private static void executable(Path file, String source) throws IOException {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private record Result(int exit, String output) {
    }
}
