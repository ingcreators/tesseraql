package io.tesseraql.maven;

import io.tesseraql.yaml.release.ReleaseDiff;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * "What does this deploy change" (roadmap Phase 46): diffs the app tree against a promotion
 * baseline and writes {@code release-diff.md} + {@code release-diff.json} beside the other
 * release evidence, logging the summary — the CI governance gate's human-readable answer to
 * what a candidate actually ships.
 */
@Mojo(name = "release-diff", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class ReleaseDiffMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", defaultValue = "${project.basedir}")
    private File appHome;

    @Parameter(property = "tesseraql.releaseDiff.baseline", required = true)
    private File baseline;

    @Parameter(property = "tesseraql.releaseDiff.outputDirectory", defaultValue = "${project.build.directory}/tesseraql-evidence")
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ReleaseDiff.Report report = ReleaseDiff.between(baseline.toPath(),
                    appHome.toPath());
            Path out = outputDirectory.toPath();
            Files.createDirectories(out);
            Files.writeString(out.resolve("release-diff.md"), ReleaseDiff.toMarkdown(report));
            Files.writeString(out.resolve("release-diff.json"), ReleaseDiff.toJson(report));
            getLog().info("Release diff: " + report.routes().size() + " route change(s), "
                    + report.api().entries().size() + " API change(s), "
                    + report.newMigrations().size() + " new migration(s) -> "
                    + out.resolve("release-diff.md"));
        } catch (Exception ex) {
            throw new MojoExecutionException("Release diff failed: " + ex.getMessage(), ex);
        }
    }
}
