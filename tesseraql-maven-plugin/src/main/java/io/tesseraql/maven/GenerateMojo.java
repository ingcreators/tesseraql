package io.tesseraql.maven;

import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generates derived artifacts from the app manifest (design ch. 22.18). This goal currently emits a
 * deterministic OpenAPI document; generated Camel routes follow with the reproducibility work.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    @Parameter(property = "tesseraql.generatedDir",
            defaultValue = "${project.build.directory}/tesseraql-generated")
    private File generatedDir;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var manifest = new ManifestLoader().load(appHome.toPath());
            String openapi = new OpenApiGenerator().toJson(manifest);
            Files.createDirectories(generatedDir.toPath());
            File output = new File(generatedDir, "openapi.json");
            Files.writeString(output.toPath(), openapi);
            getLog().info("Generated OpenAPI: " + output);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to generate artifacts", ex);
        }
    }
}
