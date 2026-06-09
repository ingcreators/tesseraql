package io.tesseraql.maven;

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

    /** The output archive path. */
    @Parameter(property = "tesseraql.output",
            defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.tqlapp")
    private File output;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            new AppPackager().pack(appHome.toPath(), output.toPath());
            getLog().info("Packaged TesseraQL app to " + output);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to package app", ex);
        }
    }
}
