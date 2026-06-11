package io.tesseraql.maven;

import io.tesseraql.core.util.Hashing;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import io.tesseraql.yaml.release.EvidenceSignature;
import io.tesseraql.yaml.release.ReleaseEvidence;
import io.tesseraql.yaml.sbom.SbomGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;

/**
 * Produces release evidence and an SBOM for an app (design ch. 49, 50): deterministic JSON tying the
 * app version to source and generated artifact hashes, plus a CycloneDX SBOM listing both the app's
 * files and the hosting project's Maven dependencies (purl, jar hash, declared licenses). With a
 * signing key configured, the evidence is signed (detached Ed25519 envelope) so consumers can verify
 * both integrity and origin; the key is read from a file and never appears in the evidence or the
 * logs.
 */
@Mojo(name = "release-evidence", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
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

    /**
     * The Ed25519 private key file (PEM or base64 PKCS#8) used to sign the evidence. The file is
     * supplied by the release pipeline (e.g. a CI secret mount) and must never be committed.
     */
    @Parameter(property = "tesseraql.signingKeyFile")
    private File signingKeyFile;

    /** The matching Ed25519 public key file (PEM or base64 X.509), embedded in the envelope. */
    @Parameter(property = "tesseraql.signingPublicKeyFile")
    private File signingPublicKeyFile;

    /** Whether the SBOM includes the hosting project's resolved Maven dependencies. */
    @Parameter(property = "tesseraql.sbom.includeDependencies", defaultValue = "true")
    private boolean includeDependencies;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private ProjectBuilder projectBuilder;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            AppManifest manifest = new ManifestLoader().load(appHome.toPath());
            Path dir = evidenceDir.toPath();
            Files.createDirectories(dir);
            String evidence = new ReleaseEvidence().toJson(manifest, appId, appVersion);
            Files.writeString(dir.resolve("release-evidence.json"), evidence);
            Files.writeString(dir.resolve("sbom.cyclonedx.json"), new SbomGenerator()
                    .toJson(manifest, appId, appVersion, mavenComponents()));
            Files.writeString(dir.resolve("openapi.json"),
                    new OpenApiGenerator().toJson(manifest));
            Files.writeString(dir.resolve("htmx-contract.json"),
                    new io.tesseraql.yaml.openapi.HtmxContractGenerator().toJson(manifest));
            if (signingKeyFile != null) {
                Files.writeString(dir.resolve("release-evidence.json.sig"), sign(evidence));
            }
            getLog().info("Wrote release evidence and SBOM to " + dir);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to write release evidence", ex);
        }
    }

    /** The project's runtime dependencies as SBOM library components. */
    private List<SbomGenerator.MavenComponent> mavenComponents() {
        if (!includeDependencies) {
            return List.of();
        }
        List<SbomGenerator.MavenComponent> components = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            String sha256 = artifact.getFile() != null && artifact.getFile().isFile()
                    ? Hashing.sha256(artifact.getFile().toPath()) : null;
            components.add(new SbomGenerator.MavenComponent(artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getVersion(), sha256, licenses(artifact)));
        }
        return components;
    }

    /** The licenses declared in the dependency's own POM; empty when the model cannot be built. */
    private List<String> licenses(Artifact artifact) {
        try {
            ProjectBuildingRequest request =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            request.setResolveDependencies(false);
            request.setProcessPlugins(false);
            return projectBuilder.build(artifact, request).getProject().getLicenses().stream()
                    .map(ReleaseEvidenceMojo::licenseName)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        } catch (ProjectBuildingException ex) {
            getLog().debug("No license information for " + artifact.getId(), ex);
            return List.of();
        }
    }

    private static String licenseName(License license) {
        return license.getName() != null ? license.getName() : license.getUrl();
    }

    private String sign(String evidence) throws MojoExecutionException, IOException {
        if (signingPublicKeyFile == null) {
            throw new MojoExecutionException("tesseraql.signingPublicKeyFile is required when "
                    + "tesseraql.signingKeyFile is set (the envelope embeds the public key)");
        }
        EvidenceSignature signature = EvidenceSignature.sign(
                evidence.getBytes(StandardCharsets.UTF_8),
                Files.readString(signingKeyFile.toPath()),
                Files.readString(signingPublicKeyFile.toPath()));
        getLog().info("Signed release evidence (Ed25519, public key sha256 "
                + signature.publicKeySha256() + ")");
        return signature.toJson();
    }
}
