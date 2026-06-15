package io.tesseraql.maven;

import io.tesseraql.report.docs.AppDocGenerator;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.openapi.HtmxContractGenerator;
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
 * Generates derived artifacts from the app manifest (design ch. 22.18): the deterministic OpenAPI
 * document, the htmx server contract, and the documentation-portal {@code docs/spec.json}. The
 * Simple YAML routes stay the source of truth; reproducibility rests on the manifest checksum index
 * and these byte-stable derivations.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    @Parameter(property = "tesseraql.generatedDir", defaultValue = "${project.build.directory}/tesseraql-generated")
    private File generatedDir;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var manifest = new ManifestLoader().load(appHome.toPath());
            Files.createDirectories(generatedDir.toPath());
            File openapi = new File(generatedDir, "openapi.json");
            Files.writeString(openapi.toPath(), new OpenApiGenerator().toJson(manifest));
            File htmx = new File(generatedDir, "htmx-contract.json");
            Files.writeString(htmx.toPath(), new HtmxContractGenerator().toJson(manifest));
            // The documentation-portal spec lives under docs/ so AppPackager can merge the whole
            // directory into the package under the reserved .tesseraql/ prefix.
            File docsDir = new File(generatedDir, "docs");
            Files.createDirectories(docsDir.toPath());
            File spec = new File(docsDir, "spec.json");
            Files.writeString(spec.toPath(), new AppDocGenerator().toJson(manifest));
            getLog().info("Generated OpenAPI: " + openapi);
            getLog().info("Generated htmx contract: " + htmx);
            getLog().info("Generated docs spec: " + spec);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to generate artifacts", ex);
        }
    }
}
