package io.tesseraql.maven;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import io.tesseraql.yaml.release.ReleaseEvidence;
import io.tesseraql.yaml.sbom.SbomGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Produces release evidence and an SBOM for an app (design ch. 49, 50): deterministic JSON tying the
 * app version to source and generated artifact hashes, plus a CycloneDX SBOM.
 */
@Mojo(name = "release-evidence", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class ReleaseEvidenceMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    @Parameter(property = "tesseraql.appId", defaultValue = "${project.groupId}.${project.artifactId}")
    private String appId;

    @Parameter(property = "tesseraql.appVersion", defaultValue = "${project.version}")
    private String appVersion;

    @Parameter(property = "tesseraql.evidenceDir",
            defaultValue = "${project.build.directory}/tesseraql-evidence")
    private File evidenceDir;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            AppManifest manifest = new ManifestLoader().load(appHome.toPath());
            Path dir = evidenceDir.toPath();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("release-evidence.json"),
                    new ReleaseEvidence().toJson(manifest, appId, appVersion));
            Files.writeString(dir.resolve("sbom.cyclonedx.json"),
                    new SbomGenerator().toJson(manifest, appId, appVersion));
            Files.writeString(dir.resolve("openapi.json"),
                    new OpenApiGenerator().toJson(manifest));
            getLog().info("Wrote release evidence and SBOM to " + dir);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to write release evidence", ex);
        }
    }
}
