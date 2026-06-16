package io.tesseraql.maven;

import io.tesseraql.apptasks.AppPackager;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Packages a TesseraQL app home into a deterministic {@code .tqlapp} archive
 * (design ch. 18 {@code package-app}, 32.3).
 */
@Mojo(name = "package-app", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class PackageAppMojo extends AbstractMojo {

    /** The external app home to package. */
    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    /** The build's generated-artifact directory; its {@code docs/} are merged into the package. */
    @Parameter(property = "tesseraql.generatedDir", defaultValue = "${project.build.directory}/tesseraql-generated")
    private File generatedDir;

    /** The output archive path. */
    @Parameter(property = "tesseraql.output", defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.tqlapp")
    private File output;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            new AppPackager().pack(appHome.toPath(),
                    new File(generatedDir, "docs").toPath(), output.toPath());
            // The sibling checksum lets installs verify package integrity (design ch. 49, 50).
            String sha256 = io.tesseraql.core.util.Hashing.sha256(output.toPath());
            java.nio.file.Files.writeString(
                    output.toPath().resolveSibling(output.getName() + ".sha256"), sha256 + "\n");
            getLog().info("Packaged TesseraQL app to " + output + " (sha256 " + sha256 + ")");
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to package app", ex);
        }
    }
}
