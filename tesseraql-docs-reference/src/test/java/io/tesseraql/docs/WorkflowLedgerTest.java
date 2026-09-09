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

    /**
     * The only place the framework's artifacts are published is Maven Central.
     *
     * <p>A second channel existed on GitHub Packages, and nothing consumed it: no POM in this
     * repository declared it under {@code <repositories>}, the CLI's module resolver never
     * contacted it, and Central has carried the same artifacts publicly and unauthenticated since
     * 0.7.1. It was worse than unused — {@code release.yml}'s Central publish declares
     * {@code needs: release}, so a 401 or a blip uploading to the channel nobody read stopped the
     * publish to the channel everyone does.
     *
     * <p>So a Maven {@code deploy} in a workflow must carry {@code -Pcentral}. That profile
     * activates {@code central-publishing-maven-plugin} with {@code <extensions>true</extensions>},
     * whose lifecycle participant replaces {@code maven-deploy-plugin} on the {@code deploy} phase —
     * measured, not read: with {@code distributionManagement} deleted, {@code -Pcentral deploy}
     * runs {@code central-publishing:publish (injected-central-publishing)} and fails at the Portal
     * with a 401, never at "repository element was not specified".
     */
    @Test
    void theOnlyPublishTargetIsMavenCentral() throws IOException {
        List<String> deploys = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (int line = 0; line < lines.size(); line++) {
                for (String shell : shellSourceAt(lines, line)) {
                    if (shell.contains("mvnw") && shell.matches(".*\\sdeploy(\\s.*)?$")
                            && !shell.contains("-Pcentral")) {
                        deploys.add(name(workflow) + ":" + (line + 1) + " " + shell.trim());
                    }
                }
            }
        }

        assertThat(deploys)
                .as("a Maven deploy that is not the Maven Central publish; the framework has one "
                        + "publish target, and a second one gates the first through the "
                        + "central-publish job's needs:")
                .isEmpty();
    }

    /**
     * A job that runs a script out of the repository has to have the repository.
     *
     * <p>v0.16.0 extracted the release choreography's polling into {@code .github/scripts/}, and
     * {@code release.yml}'s {@code bump-package-managers} — the one job of the four that never
     * checked anything out, because until then it only called {@code gh} — kept calling it as a
     * path. The job died in seven seconds with {@code exit 127} on a tag nobody could re-cut,
     * where its own timeout says it should have polled for up to forty-five minutes. Both release
     * assets were in fact on the release twelve seconds later.
     *
     * <p>This is the shape of defect the ledger exists for: it can only appear on a {@code v*}
     * tag, so no pull request reaches it, and reading the diff that introduced it shows a script
     * being called exactly as the two workflows that do check out call it. Verified red on
     * {@code release.yml}'s {@code bump-package-managers}, one of eleven jobs.
     */
    @Test
    void aJobThatRunsARepositoryScriptChecksOutTheRepository() throws IOException {
        List<String> blind = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (JobBody job : bodies(lines)) {
                if (job.body().stream().noneMatch(text -> text.contains(".github/scripts/"))) {
                    continue;
                }
                if (job.body().stream().noneMatch(text -> text.contains("actions/checkout@"))) {
                    blind.add(name(workflow) + ":" + job.job().line() + " " + job.job().id());
                }
            }
        }

        // Non-vacuity: the walk has to be finding script calls at all, or an empty result says
        // nothing. Three jobs call into .github/scripts/ today.
        assertThat(scriptCallingJobs()).as("the walk finds jobs that call a repository script")
                .isGreaterThanOrEqualTo(3);
        assertThat(blind)
                .as("jobs that run a script from .github/scripts/ without checking the repository"
                        + " out; the script is not on the runner, so the step fails with exit 127"
                        + " however long its timeout says it would have waited")
                .isEmpty();
    }

    /**
     * A job that pushes an image declares the grant that lets it.
     *
     * <p>The other half of the same tag-only failure. v0.16.0 narrowed {@code release.yml} to
     * {@code permissions: contents: write} — correct for three of its four jobs — and
     * {@code demo-image} pushes to GHCR, which needs {@code packages: write}. It built the image
     * for two minutes and then answered
     * {@code denied: installation not allowed to Write organization package}.
     *
     * <p>A job's own {@code permissions:} block replaces the workflow's rather than adding to it,
     * so the grant counts from whichever block applies to that job — which is also why the fix is
     * a job-level block and not a wider workflow-level one. Verified red on {@code demo-image}.
     */
    @Test
    void aJobThatPushesAnImageMayWritePackages() throws IOException {
        List<String> ungranted = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (JobBody job : bodies(lines)) {
                if (job.body().stream().noneMatch(text -> text.contains("docker push"))) {
                    continue;
                }
                // A job's block sits two levels in, so its entries are at six; the
                // workflow's are at two. The argument is the entries' indent.
                List<String> own = permissions(job.body(), 6);
                List<String> effective = own.isEmpty() ? permissions(lines, 2) : own;
                if (!effective.contains("packages: write")) {
                    ungranted.add(name(workflow) + ":" + job.job().line() + " " + job.job().id());
                }
            }
        }

        assertThat(imagePushingJobs()).as("the walk finds jobs that push an image")
                .isGreaterThanOrEqualTo(1);
        assertThat(ungranted)
                .as("jobs that run docker push without packages: write in the permissions block"
                        + " that applies to them; the registry refuses the push after the image"
                        + " has already been built")
                .isEmpty();
    }

    /** The entries of the first {@code permissions:} block at the given indent, stripped. */
    private static List<String> permissions(List<String> lines, int indent) {
        List<String> entries = new ArrayList<>();
        boolean inside = false;
        for (String text : lines) {
            if (text.strip().equals("permissions:") && indent(text) == indent - 2) {
                inside = true;
                continue;
            }
            if (inside) {
                if (text.isBlank() || indent(text) < indent) {
                    break;
                }
                entries.add(text.strip());
            }
        }
        return entries;
    }

    /** How many jobs across all workflows call a repository script (the non-vacuity floor). */
    private static int scriptCallingJobs() throws IOException {
        return matchingJobs(text -> text.contains(".github/scripts/"));
    }

    /** How many jobs across all workflows push an image (the non-vacuity floor). */
    private static int imagePushingJobs() throws IOException {
        return matchingJobs(text -> text.contains("docker push"));
    }

    private static int matchingJobs(java.util.function.Predicate<String> line) throws IOException {
        int count = 0;
        for (Path workflow : workflows()) {
            for (JobBody job : bodies(Files.readAllLines(workflow))) {
                if (job.body().stream().anyMatch(line)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** One job with the lines it spans, so a body can be searched without re-deriving bounds. */
    private record JobBody(Job job, List<String> body) {
    }

    /** Each job of one workflow paired with its own lines, bounded by the next job's opening. */
    private static List<JobBody> bodies(List<String> lines) {
        List<Job> found = jobs(lines);
        List<JobBody> bodies = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int from = found.get(i).line() - 1;
            int to = i + 1 < found.size() ? found.get(i + 1).line() - 1 : lines.size();
            bodies.add(new JobBody(found.get(i), lines.subList(from, to)));
        }
        return bodies;
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
