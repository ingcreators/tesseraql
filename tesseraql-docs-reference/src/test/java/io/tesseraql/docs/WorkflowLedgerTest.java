package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The workflow ledger (docs/release-and-ci-hardening.md, decision 1): the claims this repository
 * makes about its own build, held by a test instead of by review.
 *
 * <p>The release path is the only path the project cannot rehearse — {@code release.yml} gates its
 * job on a {@code v*} tag push, so no pull request enters it and the only recovery gesture is
 * GitHub's "Re-run failed jobs". And until this file, nothing here looked at {@code .github/} at
 * all: of the twenty-one tests in this module, {@code NoConflictMarkersTest} was the only one that
 * read a workflow, and it read it for merge-conflict markers. Everything else about them was held
 * by review alone, and drifted accordingly.
 *
 * <p>Each assertion was verified <b>red</b> before its fix landed, and each fix shipped in the
 * same pull request — a guard that arrives a PR after its fix is a guard nobody saw fail.
 *
 * <p>This resolves {@code .github/workflows} directly rather than walking the repository root.
 * {@code NoConflictMarkersTest} and {@code AppNameReadLedgerTest} both carry an explicit skip for
 * {@code /.claude/} because that directory holds worktrees, and a walk would read another branch's
 * in-progress workflow files. Listing one directory has no such failure mode, and there is no
 * second place a workflow can live.
 */
class WorkflowLedgerTest {

    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    /** {@code uses: owner/repo@ref}, with the optional list dash and any trailing comment. */
    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*(\\S+)");

    /** A commit SHA as GitHub writes one: forty lowercase hex digits. */
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    /** The opening of a {@code run:} step, either a block scalar or a one-liner. */
    private static final Pattern RUN = Pattern
            .compile("^(\\s*)(?:-\\s+)?run:(\\s*[|>][-+0-9]*\\s*)?(.*)$");

    /**
     * An action pinned to a tag or a branch is whatever that ref points at when the job starts.
     *
     * <p>{@code .github/dependabot.yml} carries the {@code github-actions} ecosystem, so the SHA
     * form is maintained rather than frozen: Dependabot rewrites the pin and the version comment
     * together. Verified red on {@code jpackage.yml:144} and {@code :429}, two of thirty.
     */
    @Test
    void everyActionIsPinnedToACommitSha() throws IOException {
        List<String> floating = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (int line = 0; line < lines.size(); line++) {
                Matcher matcher = USES.matcher(lines.get(line));
                if (!matcher.find()) {
                    continue;
                }
                String reference = matcher.group(1);
                // A local action (./path) and a container (docker://…) carry no ref to pin.
                int at = reference.lastIndexOf('@');
                if (reference.startsWith("./") || reference.startsWith("docker://") || at < 0) {
                    continue;
                }
                if (!SHA.matcher(reference.substring(at + 1)).matches()) {
                    floating.add(name(workflow) + ":" + (line + 1) + " " + reference);
                }
            }
        }

        assertThat(floating)
                .as("actions pinned to a mutable ref; pin the 40-character commit SHA and keep the "
                        + "version in a trailing comment, as every other uses: line here does")
                .isEmpty();
    }

    /**
     * A {@code ${{ … }}} inside a {@code run:} body is substituted into the shell source before
     * the shell parses it, so the value becomes program text rather than data.
     *
     * <p>None of the four this was red on is reachable without push access, so this is hygiene
     * rather than a vulnerability (docs/release-and-ci-hardening.md, decision 7) — but hoisting the
     * value to {@code env:} is how that hygiene is written down, and it is the shape that stays
     * correct when a future step interpolates something a stranger controls. Verified red on
     * {@code release.yml:276}, {@code :279}, {@code :283} and {@code :285}.
     *
     * <p>Only {@code run:} bodies are scanned. {@code if:}, {@code with:}, {@code env:} and
     * {@code name:} are GitHub's own expression contexts, never shell source, and every workflow
     * here uses them.
     */
    @Test
    void noWorkflowInterpolatesAnExpressionIntoAShell() throws IOException {
        List<String> interpolations = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (int line = 0; line < lines.size(); line++) {
                for (String shell : shellSourceAt(lines, line)) {
                    if (shell.contains("${{")) {
                        interpolations.add(name(workflow) + ":" + (line + 1) + " " + shell.trim());
                    }
                }
            }
        }

        assertThat(interpolations)
                .as("GitHub expressions substituted into shell source; bind the value to an env: "
                        + "entry on the step and read it as \"$NAME\", so the shell sees data")
                .isEmpty();
    }

    /**
     * A job with no {@code timeout-minutes} runs under GitHub's default of six hours.
     *
     * <p>Two of these jobs hold a required check, so an unbounded one holds the branch as well as
     * the runner. The bounds are measured rather than guessed — see docs/release-and-ci-hardening.md
     * — and one of them cannot be measured from a green run at all: {@code bump-package-managers}
     * finishes in seconds today only because it soft-skips on absent credentials, and its real path
     * polls the release assets for twenty minutes.
     *
     * <p>The key is read at <b>job</b> depth only. GitHub accepts the same key on a step, and a job
     * that bounds one step is not a bounded job — an assertion that matched at any depth would go
     * green on the wrong thing. Verified red on 13 of 14 jobs; only {@code dialects.yml} had one.
     */
    @Test
    void everyJobDeclaresATimeout() throws IOException {
        List<String> unbounded = new ArrayList<>();
        for (Path workflow : workflows()) {
            for (Job job : jobs(Files.readAllLines(workflow))) {
                if (!job.bounded()) {
                    unbounded.add(name(workflow) + ":" + job.line() + " " + job.id());
                }
            }
        }

        assertThat(unbounded)
                .as("jobs with no timeout-minutes, so bounded only by GitHub's six-hour default; "
                        + "give each one a bound taken from its measured runtime")
                .isEmpty();
    }

    /**
     * The Maven distribution the wrapper fetches is verified against a checksum this repo recorded.
     *
     * <p>{@code mvnw:226} validates only when {@code distributionSha256Sum} is present, so its
     * absence is silent: every cold run downloads and executes a zip nobody checked.
     * {@code setup-java} caches the distribution for eight of the ten {@code ./mvnw} sites, but not
     * for the two Docker builds — {@code ci.yml} and {@code release.yml}, the second on the tag path
     * — which fetch it fresh every run.
     *
     * <p>The value was derived, never pasted: the zip's PGP signature was verified against the ASF
     * KEYS from {@code downloads.apache.org}, and the SHA-256 taken from those same bytes. Both
     * directions were rehearsed — a cold download validates, and a wrong value stops the build with
     * "Failed to validate Maven distribution SHA-256".
     *
     * <p>The property has no {@code wrapper:wrapper} parameter, so a Dependabot bump of the
     * distribution will leave it stale. That fails loudly, which is the point, but the recipe has to
     * be written down: see docs/release-and-ci-hardening.md.
     */
    @Test
    void theMavenDistributionIsFetchedAgainstAChecksum() throws IOException {
        Path properties = Path.of("..", ".mvn", "wrapper", "maven-wrapper.properties");
        List<String> lines = Files.readAllLines(properties);
        assertThat(lines).as("the wrapper properties could not be read").isNotEmpty();

        String checksum = lines.stream()
                .filter(line -> line.startsWith("distributionSha256Sum="))
                .map(line -> line.substring("distributionSha256Sum=".length()).trim())
                .findFirst().orElse("");

        assertThat(checksum)
                .as(".mvn/wrapper/maven-wrapper.properties declares no distributionSha256Sum, so "
                        + "mvnw executes whatever it downloaded; derive it from the signed "
                        + "distribution rather than copying a value")
                .matches("[0-9a-f]{64}");
    }

    /**
     * The release choreography's polling lives in a script, not copied into a workflow.
     *
     * <p>Three copies of {@code for i in $(seq 1 60); do … sleep 20} waited on two different
     * things: the two in {@code jpackage.yml} waited for the release {@code release.yml} creates,
     * and the one in {@code release.yml} waited for the assets {@code jpackage.yml} attaches. So
     * they are two scripts, not one, and neither belongs inline: a workflow step cannot be run
     * anywhere, and this is the choreography on the one path no pull request enters.
     *
     * <p>The predicate is a counting loop that <b>talks to the release API</b>, not any counting
     * loop. Written the broad way it is red on {@code ci.yml}'s dev-server readiness wait, which is
     * honest: that one polls a local port, is bounded at three minutes, and breaks early when the
     * process it is waiting for dies. A guard that is red for an honest reason is one somebody
     * eventually deletes.
     *
     * <p>The check is on the workflows only. The scripts themselves poll, by definition.
     */
    @Test
    void noWorkflowCarriesItsOwnReleasePollingLoop() throws IOException {
        List<String> loops = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (int line = 0; line < lines.size(); line++) {
                String text = lines.get(line);
                if (!text.contains("for ") || !text.contains("seq ")) {
                    continue;
                }
                if (bodyOf(lines, line).contains("gh release")) {
                    loops.add(name(workflow) + ":" + (line + 1) + " " + text.trim());
                }
            }
        }

        assertThat(loops)
                .as("release-choreography polling written inline in a workflow; put the wait in "
                        + ".github/scripts/ where it can be read, rehearsed and fixed once")
                .isEmpty();
    }

    /** The lines of the loop opening at {@code start}: everything indented under it. */
    private static String bodyOf(List<String> lines, int start) {
        int indent = indent(lines.get(start));
        StringBuilder body = new StringBuilder();
        for (int line = start + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (!text.isBlank() && indent(text) <= indent) {
                // "done" closes the loop at the opening indent; anything shallower ends the block.
                if (text.strip().startsWith("done")) {
                    body.append(text).append('\n');
                }
                break;
            }
            body.append(text).append('\n');
        }
        return body.toString();
    }

    /**
     * A workflow a pull request can trigger grants no write permission at its top level.
     *
     * <p>{@code jpackage.yml} granted {@code contents: write} for the whole workflow and triggers
     * on {@code pull_request}, so every same-repo pull request ran {@code ./mvnw} — arbitrary
     * branch code — holding a token that could write to the repository.
     *
     * <p>The top level is the only place this can be asserted. {@code permissions:} takes no
     * expression, and the image jobs run on both events, so a job-level grant is the same grant;
     * the fix is a separate tag-gated job that does the writing, which is what the workflow now
     * has. A job-level {@code write} is therefore still allowed here, and is where a reviewer
     * should look.
     *
     * <p>A fork's {@code GITHUB_TOKEN} is read-only whatever the block says, so this is
     * defence-in-depth against a compromised account with push access rather than a hole anyone
     * can walk through.
     */
    @Test
    void aPullRequestNeverRunsUnderAWorkflowWideWriteGrant() throws IOException {
        List<String> granted = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            if (!triggersOnPullRequest(lines)) {
                continue;
            }
            boolean inPermissions = false;
            for (int line = 0; line < lines.size(); line++) {
                String text = lines.get(line);
                if (text.startsWith("permissions:")) {
                    inPermissions = true;
                    continue;
                }
                if (inPermissions) {
                    if (!text.startsWith(" ") || text.isBlank()) {
                        break;
                    }
                    if (text.strip().endsWith(": write")) {
                        granted.add(name(workflow) + ":" + (line + 1) + " " + text.strip());
                    }
                }
            }
        }

        assertThat(granted)
                .as("workflow-wide write grants on a workflow a pull request can trigger; move the "
                        + "writing into its own tag-gated job with its own permissions block, "
                        + "because permissions: takes no expression and a job that runs on both "
                        + "events cannot be narrowed any other way")
                .isEmpty();
    }

    /** Whether the workflow's {@code on:} block names {@code pull_request}. */
    private static boolean triggersOnPullRequest(List<String> lines) {
        boolean inOn = false;
        for (String text : lines) {
            if (text.startsWith("on:")) {
                inOn = true;
                continue;
            }
            if (inOn) {
                if (!text.startsWith(" ") && !text.isBlank()) {
                    return false;
                }
                if (text.strip().startsWith("pull_request")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One job block: its id, the line it opens on, and whether it declares its own timeout. */
    private record Job(String id, int line, boolean bounded) {
    }

    /** A job key sits two spaces in, under the top-level {@code jobs:} mapping. */
    private static final Pattern JOB = Pattern.compile("^ {2}([A-Za-z0-9_-]+):\\s*$");

    /** The bound, at job depth. Four spaces, not eight: eight would be a step's. */
    private static final Pattern JOB_TIMEOUT = Pattern
            .compile("^ {4}timeout-minutes:\\s*\\d+\\s*$");

    /** The job blocks of one workflow, in file order. */
    private static List<Job> jobs(List<String> lines) {
        List<Job> jobs = new ArrayList<>();
        String open = null;
        int opened = 0;
        boolean bounded = false;
        boolean inJobs = false;
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.equals("jobs:")) {
                inJobs = true;
                continue;
            }
            if (!inJobs || text.isBlank() || text.stripLeading().startsWith("#")) {
                continue;
            }
            // A key back at column zero ends the jobs mapping.
            if (indent(text) == 0) {
                inJobs = false;
            }
            Matcher job = JOB.matcher(text);
            if (!inJobs || job.matches()) {
                if (open != null) {
                    jobs.add(new Job(open, opened, bounded));
                    open = null;
                }
                if (!inJobs) {
                    continue;
                }
                open = job.group(1);
                opened = line + 1;
                bounded = false;
            } else if (open != null && JOB_TIMEOUT.matcher(text).matches()) {
                bounded = true;
            }
        }
        if (open != null) {
            jobs.add(new Job(open, opened, bounded));
        }
        return jobs;
    }

    /**
     * The shell source contributed by line {@code index}: the inline remainder of a {@code run:},
     * or — when {@code index} is inside a block scalar a {@code run:} opened — that line itself.
     */
    private static List<String> shellSourceAt(List<String> lines, int index) {
        String line = lines.get(index);
        Matcher run = RUN.matcher(line);
        if (run.matches()) {
            return run.group(2) == null ? List.of(run.group(3)) : List.of();
        }
        // Walk back past this line's siblings — the rest of the block body — to the first
        // less-indented line. That line either opened the block scalar or is something else,
        // and only the first case makes this line shell source. Walking back must not stop at a
        // sibling: the third line of a run: body is as much shell as the first.
        for (int above = index - 1; above >= 0; above--) {
            String candidate = lines.get(above);
            if (candidate.isBlank() || indent(candidate) >= indent(line)) {
                continue;
            }
            Matcher opening = RUN.matcher(candidate);
            return opening.matches() && opening.group(2) != null ? List.of(line) : List.of();
        }
        return List.of();
    }

    private static int indent(String line) {
        return line.length() - line.stripLeading().length();
    }

    /** The workflow files, sorted, failing loudly rather than passing on an empty directory. */
    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            List<Path> found = files.filter(path -> path.toString().endsWith(".yml"))
                    .sorted().toList();
            assertThat(found)
                    .as("no workflow files found at " + WORKFLOWS.toAbsolutePath().normalize()
                            + "; this guard would pass vacuously")
                    .isNotEmpty();
            return found;
        }
    }

    private static String name(Path workflow) {
        return ".github/workflows/" + workflow.getFileName();
    }
}
