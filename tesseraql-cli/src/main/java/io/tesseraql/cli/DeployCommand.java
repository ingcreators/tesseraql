package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.TesseraqlVersion;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.version.SemanticVersion;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.operations.app.AppUpgrader;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql deploy}: the operator's pen for the install root's deploy protocol
 * (docs/runtime-replace.md structural decision 3). The command writes intent —
 * {@code catalog.json} and {@code .upgrade/<name>.json}, through {@link AppUpgrader} — and a
 * running host's reconciler converges to it, replacing one application's runtime while the stack
 * keeps serving. With no host running the command still works: the state is written, and the next
 * host start converges to it, which is the file protocol's whole point.
 *
 * <p>The package is a local path; getting bytes onto the host stays the deployment's concern
 * (hosting.md's no-fetcher stance). {@code --stack} is explicit like {@code host}'s, because
 * production does not guess (docs/cli-surface.md decision 9). {@code --wait} tails the status
 * file the host alone writes, so a CI pipeline gets a synchronous exit code out of an
 * asynchronous host.
 */
@Command(name = "deploy", description = "Deploy one application into a stack's install root; a"
        + " running host replaces its runtime without a restart.", subcommands = {
                DeployCommand.WeightCommand.class,
                DeployCommand.PromoteCommand.class,
                DeployCommand.RollbackCommand.class,
                DeployCommand.StatusCommand.class
        })
final class DeployCommand implements Callable<Integer> {

    private static final TqlErrorCode NOT_AN_INSTALL_ROOT = new TqlErrorCode(TqlDomain.UPGRADE,
            4092);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_MILLIS = 200;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<package.tqlapp>", description = "The"
            + " application package to deploy. Its declared name says which member it replaces;"
            + " its version must be newer than the installed one.")
    Path tqlapp;

    @Option(names = {"--stack"}, paramLabel = "<dir>", description = "The install root to deploy"
            + " into: catalog.json plus one unpacked tree per application version. Explicit,"
            + " never discovered - production does not guess.")
    Path stack;

    @Option(names = {"--url"}, paramLabel = "<origin>", description = "Deploy remotely instead:"
            + " the stack's origin, whose authenticated deploy endpoint checks the caller's"
            + " tql.app.deploy.<name> grant against the package's declared name and writes the"
            + " same intent. The bearer comes from `tesseraql token` via TESSERAQL_TOKEN or"
            + " --token-file.")
    String url;

    @Option(names = {"--token-file"}, paramLabel = "<file>", description = "File holding the"
            + " bearer token for --url (else TESSERAQL_TOKEN) - never a command-line argument,"
            + " so it cannot leak into shell history or process listings.")
    Path tokenFile;

    @Option(names = {"--canary"}, description = "Stage the new version beside the serving one"
            + " instead of replacing it, at --weight percent of HTTP traffic (default 10)."
            + " Promote or roll back when the ramp has said its piece.")
    boolean canary;

    @Option(names = {"--weight"}, paramLabel = "<percent>", description = "The staged canary's"
            + " share of HTTP traffic, 0-100. Only with --canary; background work participates"
            + " fully from candidate start regardless.")
    Integer weight;

    @Option(names = {"--sha256"}, paramLabel = "<hex>", description = "Verify the package's"
            + " SHA-256 before anything is written; a tampered or corrupted package is rejected.")
    String sha256;

    @Option(names = {"--wait"}, description = "Wait for the running host to report the outcome"
            + " in the member's .upgrade status file before exiting.")
    boolean wait;

    @Option(names = {"--wait-timeout"}, paramLabel = "<seconds>", description = "How long --wait"
            + " waits before giving up loudly (default 300).")
    long waitTimeout = 300;

    @Override
    public Integer call() {
        if (tqlapp == null) {
            CommandLine.usage(this, System.out);
            return 2;
        }
        // The dual shape token itself has (docs/stack-shells.md, the deploy surface):
        // --stack writes the install root directly, --url asks the running stack's
        // authenticated endpoint to. One or the other, never both, never neither.
        if (stack != null && url != null) {
            System.err.println("Choose one: --stack <dir> writes the install root on this"
                    + " machine, --url <origin> deploys through the running stack's"
                    + " authenticated endpoint.");
            return 2;
        }
        if (stack == null && url == null) {
            System.err.println("Pass --stack <dir> (the install root to deploy into - explicit,"
                    + " never discovered) or --url <origin> (a running stack's deploy"
                    + " endpoint).");
            return 2;
        }
        if (url != null && wait) {
            System.err.println("--wait tails the install root's status file, which --url cannot"
                    + " see. The endpoint already answers refusals synchronously; watch"
                    + " convergence with `deploy status --stack <dir>` on the host.");
            return 2;
        }
        if (weight != null && !canary) {
            System.err.println("--weight is the staged canary's traffic share; pass it with"
                    + " --canary, or use 'deploy weight <name> <percent>' to move a running"
                    + " ramp.");
            return 2;
        }
        if (!Files.isRegularFile(tqlapp)) {
            System.err.println("No such package: " + tqlapp);
            return 2;
        }
        if (url != null) {
            return deployRemotely();
        }
        try {
            requireInstallRoot(stack);
            String name = new AppInstaller().peek(tqlapp).name();
            byte[] before = statusSnapshot(stack, name);
            AppUpgrader upgrader = new AppUpgrader();
            AppUpgrader.UpgradeResult result = sha256 != null
                    ? upgrader.upgrade(tqlapp, stack, frameworkVersion(), canary, sha256)
                    : upgrader.upgrade(tqlapp, stack, frameworkVersion(), canary);
            if (canary && weight != null) {
                upgrader.setCanaryWeight(result.appName(), stack, weight);
            }
            if (canary) {
                System.out.println("Staged '" + result.appName() + "' " + result.toVersion()
                        + " as a canary at " + (weight == null ? 10 : weight) + "%"
                        + (result.fromVersion() == null
                                ? ""
                                : " beside the serving " + result.fromVersion())
                        + ".");
            } else {
                System.out.println("Deployed '" + result.appName() + "' " + result.toVersion()
                        + (result.fromVersion() == null
                                ? ""
                                : " (was " + result.fromVersion() + ")")
                        + ". A running host converges to it; otherwise the next start serves"
                        + " it.");
            }
            return wait ? awaitOutcome(stack, result.appName(), before, waitTimeout) : 0;
        } catch (TqlException refused) {
            System.err.println(refused.getMessage());
            return 2;
        }
    }

    /** The framework version the preflight gates on ({@code requires.framework}). */
    static SemanticVersion frameworkVersion() {
        return SemanticVersion.parse(TesseraqlVersion.current());
    }

    /**
     * The remote mode: the package rides the request body to the stack's authenticated deploy
     * endpoint, which checks the caller's {@code tql.app.deploy.<name>} against the package's
     * declared name, preflights, and writes the intent on its own install root — so a pipeline
     * deploys one application with a scoped token and no install-root access
     * (docs/stack-shells.md, the deploy surface).
     */
    private Integer deployRemotely() {
        String token;
        try {
            token = bearerToken();
        } catch (Exception missing) {
            System.err.println(missing.getMessage());
            return 2;
        }
        StringBuilder query = new StringBuilder();
        if (canary) {
            query.append("canary=true");
            if (weight != null) {
                query.append("&weight=").append(weight);
            }
        }
        if (sha256 != null) {
            query.append(query.isEmpty() ? "" : "&").append("sha256=").append(sha256);
        }
        String target = url.replaceAll("/+$", "") + "/_tesseraql/deploy"
                + (query.isEmpty() ? "" : "?" + query);
        try {
            // Bounded like every other outbound call (docs/duplication-consolidation.md,
            // campaign 1): this upload had no timeout at all. The request bound is generous
            // because a bundle is tens of megabytes over whatever link reaches the stack.
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build()
                    .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
                            .timeout(java.time.Duration.ofMinutes(5))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/octet-stream")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofFile(tqlapp))
                            .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<?, ?> body = MAPPER.readValue(response.body(), Map.class);
                boolean staged = Boolean.TRUE.equals(body.get("canary"));
                System.out.println((staged ? "Staged '" : "Deployed '") + body.get("name")
                        + "' " + body.get("toVersion")
                        + (body.get("fromVersion") == null
                                ? ""
                                : (staged ? " beside the serving " : " (was ")
                                        + body.get("fromVersion") + (staged ? "" : ")"))
                        + ". The stack's host converges to it.");
                return 0;
            }
            System.err.println("The stack refused it (HTTP " + response.statusCode() + "): "
                    + refusalMessage(response.body()));
            return 2;
        } catch (IOException unreachable) {
            System.err.println("Could not reach " + target + ": " + unreachable.getMessage());
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** {@code --token-file}, then {@code TESSERAQL_TOKEN} — never a command-line argument. */
    private String bearerToken() throws IOException {
        if (tokenFile != null) {
            return Files.readString(tokenFile).trim();
        }
        String env = System.getenv("TESSERAQL_TOKEN");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        throw new IOException("No bearer for --url: set TESSERAQL_TOKEN or pass --token-file"
                + " <file>. `tesseraql token --url " + url + " --login <id>` mints one from"
                + " your account.");
    }

    /** The refusal's message out of the endpoint's error envelope, else the raw body. */
    private static String refusalMessage(String body) {
        try {
            Map<?, ?> parsed = MAPPER.readValue(body, Map.class);
            if (parsed.get("error") instanceof Map<?, ?> error
                    && error.get("message") != null) {
                return error.get("code") + " " + error.get("message");
            }
        } catch (IOException notJson) {
            // fall through to the raw body
        }
        return body;
    }

    /**
     * Refuses a {@code --stack} that is not an install root: no catalogue means no versions and
     * no ledger to deploy into.
     */
    static void requireInstallRoot(Path stack) {
        if (!Files.isRegularFile(stack.resolve("catalog.json"))) {
            throw new TqlException(NOT_AN_INSTALL_ROOT, "Not an install root: " + stack
                    + " holds no catalog.json. deploy writes versions into an install root -"
                    + " the catalogue plus one unpacked tree per application version, the shape"
                    + " host serves in production. A workspace of source trees has no version"
                    + " ledger: deploy there by restarting the stack.");
        }
    }

    /** The status file's bytes before the intent write, so --wait sees only a fresh report. */
    static byte[] statusSnapshot(Path stack, String name) {
        Path file = statusFile(stack, name);
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    static Path statusFile(Path stack, String name) {
        return stack.resolve(".upgrade").resolve(name + ".status.json");
    }

    /**
     * Tails the status file the host alone writes until it changes, then renders the outcome as
     * an exit code: applied is 0, refused is 2, and a timeout is a loud 1 — the intent is
     * written either way, so a timeout is "the host has not answered", not "the deploy failed".
     */
    static Integer awaitOutcome(Path stack, String name, byte[] before, long timeoutSeconds) {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        Path file = statusFile(stack, name);
        while (System.nanoTime() < deadline) {
            byte[] now = statusSnapshot(stack, name);
            if (now != null && !Arrays.equals(now, before)) {
                Map<?, ?> status;
                try {
                    status = MAPPER.readValue(now, Map.class);
                } catch (IOException torn) {
                    status = null;
                }
                if (status != null && status.get("outcome") != null) {
                    if ("applied".equals(status.get("outcome"))) {
                        System.out.println("The host applied it: " + status.get("action") + " v"
                                + status.get("version") + ".");
                        return 0;
                    }
                    System.err.println("The host refused it: " + status.get("message"));
                    return 2;
                }
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
        System.err.println("Timed out after " + timeoutSeconds + "s waiting for the host to"
                + " report on '" + name + "'. The intent is written - a running host converges"
                + " shortly, and with no host running the next start serves it. The host"
                + " reports in " + file + ".");
        return 1;
    }

    /** {@code tesseraql deploy weight <name> <percent> --stack <dir>}: move the running ramp. */
    @Command(name = "weight", description = "Adjust the staged canary's share of HTTP traffic.")
    static final class WeightCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<name>", description = "The application whose"
                + " canary is staged.")
        String name;

        @Parameters(index = "1", paramLabel = "<percent>", description = "The new share, 0-100.")
        int percent;

        @Option(names = {"--stack"}, required = true, paramLabel = "<dir>", description = "The"
                + " install root.")
        Path stack;

        @Override
        public Integer call() {
            try {
                requireInstallRoot(stack);
                new AppUpgrader().setCanaryWeight(name, stack, percent);
                System.out.println("Canary weight for '" + name + "' is now " + percent + "%.");
                return 0;
            } catch (TqlException refused) {
                System.err.println(refused.getMessage());
                return 2;
            }
        }
    }

    /** {@code tesseraql deploy promote <name> --stack <dir>}: the candidate goes active. */
    @Command(name = "promote", description = "Activate the staged canary; the previous version"
            + " stays on disk for rollback.")
    static final class PromoteCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<name>", description = "The application whose"
                + " canary is staged.")
        String name;

        @Option(names = {"--stack"}, required = true, paramLabel = "<dir>", description = "The"
                + " install root.")
        Path stack;

        @Option(names = {"--wait"}, description = "Wait for the running host to report the"
                + " outcome in the member's .upgrade status file before exiting.")
        boolean wait;

        @Option(names = {"--wait-timeout"}, paramLabel = "<seconds>", description = "How long"
                + " --wait waits before giving up loudly (default 300).")
        long waitTimeout = 300;

        @Override
        public Integer call() {
            try {
                requireInstallRoot(stack);
                byte[] before = statusSnapshot(stack, name);
                InstalledApp promoted = new AppUpgrader().promote(name, stack);
                System.out.println("Promoted '" + name + "' to " + promoted.version()
                        + "; the previous version stays on disk for rollback.");
                return wait ? awaitOutcome(stack, name, before, waitTimeout) : 0;
            } catch (TqlException refused) {
                System.err.println(refused.getMessage());
                return 2;
            }
        }
    }

    /**
     * {@code tesseraql deploy rollback <name> --stack <dir>}: discard a staged canary, or move
     * the catalogue back onto the previous version's files.
     */
    @Command(name = "rollback", description = "Discard a staged canary, or restore the previous"
            + " version as active.")
    static final class RollbackCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<name>", description = "The application to roll"
                + " back.")
        String name;

        @Option(names = {"--stack"}, required = true, paramLabel = "<dir>", description = "The"
                + " install root.")
        Path stack;

        @Override
        public Integer call() {
            try {
                requireInstallRoot(stack);
                AppUpgrader upgrader = new AppUpgrader();
                boolean staged = upgrader.canary(name, stack).isPresent();
                InstalledApp active = upgrader.rollback(name, stack);
                if (staged) {
                    System.out.println("Discarded the staged candidate; '" + name + "' stays at "
                            + (active == null ? "its serving version" : active.version()) + ".");
                } else {
                    System.out.println("Rolled back '" + name + "' to " + active.version()
                            + ".");
                }
                return 0;
            } catch (TqlException refused) {
                System.err.println(refused.getMessage());
                return 2;
            }
        }
    }

    /**
     * {@code tesseraql deploy status [<name>] --stack <dir>}: reads back both sides of the
     * protocol — the intent files the CLI writes, and the status file the host writes.
     */
    @Command(name = "status", description = "Show each member's active version, staged canary,"
            + " and the host's last reported outcome.")
    static final class StatusCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", paramLabel = "<name>", description = "One"
                + " application; every catalogued one when omitted.")
        String name;

        @Option(names = {"--stack"}, required = true, paramLabel = "<dir>", description = "The"
                + " install root.")
        Path stack;

        @Override
        public Integer call() {
            try {
                requireInstallRoot(stack);
                AppCatalog catalog = new AppCatalog(stack);
                List<InstalledApp> members = name == null
                        ? catalog.list()
                        : catalog.find(name).map(List::of).orElse(List.of());
                if (name != null && members.isEmpty()) {
                    System.err.println("The catalogue holds no '" + name + "'.");
                    return 2;
                }
                AppUpgrader upgrader = new AppUpgrader();
                for (InstalledApp member : members) {
                    System.out.println(member.name() + ": active " + member.version());
                    Optional<AppUpgrader.CanaryStatus> staged = upgrader.canary(member.name(),
                            stack);
                    staged.ifPresent(canary -> System.out.println("  canary: "
                            + canary.candidate().version() + " at " + canary.weightPercent()
                            + "%"));
                    lastOutcome(stack, member.name())
                            .ifPresent(line -> System.out.println("  host: " + line));
                }
                return 0;
            } catch (TqlException refused) {
                System.err.println(refused.getMessage());
                return 2;
            }
        }

        private static Optional<String> lastOutcome(Path stack, String name) {
            byte[] bytes = statusSnapshot(stack, name);
            if (bytes == null) {
                return Optional.empty();
            }
            try {
                Map<?, ?> status = MAPPER.readValue(bytes, Map.class);
                if ("applied".equals(status.get("outcome"))) {
                    return Optional.of("applied " + status.get("action") + " v"
                            + status.get("version") + " at " + status.get("at"));
                }
                return Optional.of("refused: " + status.get("message"));
            } catch (IOException torn) {
                return Optional.empty();
            }
        }
    }
}
