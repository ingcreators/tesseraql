package io.tesseraql.maven;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.release.ReleaseEvidenceVerifier;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Verifies a release evidence document against the current app sources (design ch. 49): every
 * recorded hash must match, and the detached signature - when present or required - must verify,
 * optionally pinned to an expected public key fingerprint. Run it at deploy time to prove the app
 * being shipped is exactly the one the evidence was produced (and signed) for.
 */
@Mojo(name = "verify-evidence", threadSafe = true)
public class VerifyEvidenceMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    @Parameter(property = "tesseraql.evidenceFile",
            defaultValue = "${project.build.directory}/tesseraql-evidence/release-evidence.json")
    private File evidenceFile;

    /** Whether a missing signature envelope fails the verification. */
    @Parameter(property = "tesseraql.requireSignature", defaultValue = "false")
    private boolean requireSignature;

    /** The SHA-256 fingerprint of the public key the evidence must be signed with. */
    @Parameter(property = "tesseraql.expectedKeySha256")
    private String expectedKeySha256;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        AppManifest manifest = new ManifestLoader().load(appHome.toPath());
        Path signatureFile = evidenceFile.toPath()
                .resolveSibling(evidenceFile.getName() + ".sig");
        try {
            String evidence = Files.readString(evidenceFile.toPath());
            String signature = Files.isRegularFile(signatureFile)
                    ? Files.readString(signatureFile) : null;
            if (signature == null && requireSignature) {
                throw new MojoFailureException(
                        "Evidence signature required but " + signatureFile + " is missing");
            }
            ReleaseEvidenceVerifier.Result result = new ReleaseEvidenceVerifier()
                    .verify(manifest, evidence, signature, blankToNull(expectedKeySha256));
            for (ReleaseEvidenceVerifier.Mismatch mismatch : result.mismatches()) {
                getLog().error(mismatch.subject() + ": " + mismatch.reason());
            }
            if (!result.verified()) {
                throw new MojoFailureException("Release evidence verification failed: "
                        + result.mismatches().size() + " mismatch(es)");
            }
            getLog().info("Release evidence verified against " + appHome
                    + (signature != null ? " (signature valid)" : ""));
        } catch (IOException ex) {
            throw new MojoExecutionException("Cannot read evidence: " + ex.getMessage(), ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
