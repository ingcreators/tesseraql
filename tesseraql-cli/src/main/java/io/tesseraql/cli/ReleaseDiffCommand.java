package io.tesseraql.cli;

import io.tesseraql.yaml.release.ReleaseDiff;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * "What does this deploy change" (roadmap Phase 46): diffs two app trees — the promotion
 * baseline against the candidate — and prints the release report: routes, the OpenAPI
 * contract, the migrations the deploy will run, policy changes, and the table-level schema
 * delta when both trees carry the introspection sidecar. Pure file reads, deterministic
 * output — drop it into a PR comment or a CI governance gate.
 */
@Command(name = "release-diff", description = "Diff two app trees: what does deploying the candidate change.")
public final class ReleaseDiffCommand implements Callable<Integer> {

    @Option(names = {
            "--app"}, required = true, description = "Path to the candidate app home (what you are about to deploy).")
    Path app;

    @Option(names = {
            "--baseline"}, required = true, description = "Path to the baseline app home (what runs today: a checkout of the "
                    + "deployed tag or an unpacked release).")
    Path baseline;

    @Option(names = {"--json"}, description = "Emit JSON instead of Markdown.")
    boolean json;

    @Option(names = {"--out"}, description = "Also write the report to this file.")
    Path out;

    @Override
    public Integer call() throws Exception {
        ReleaseDiff.Report report = ReleaseDiff.between(baseline, app);
        String rendered = json ? ReleaseDiff.toJson(report) : ReleaseDiff.toMarkdown(report);
        System.out.println(rendered);
        if (out != null) {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, rendered);
        }
        return 0;
    }
}
