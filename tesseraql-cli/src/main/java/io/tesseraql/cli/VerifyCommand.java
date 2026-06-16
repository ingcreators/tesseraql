package io.tesseraql.cli;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.release.ReleaseEvidenceVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql verify --app <dir> --evidence-file <file>}: verifies a release evidence document
 * against the current app sources (design ch. 49) — every recorded hash must match and, when a
 * detached signature is present or required, it must verify. This is the consumer-side counterpart
 * of the Maven/CI-only {@code release-evidence} producer: read-only, no keys.
 */
@Command(name = "verify", description = "Verify release evidence against the app sources.")
final class VerifyCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Option(names = {
            "--evidence-file"}, required = true, description = "The release-evidence.json to verify (its sibling .sig is auto-detected).")
    Path evidenceFile;

    @Option(names = {
            "--require-signature"}, description = "Fail if no signature envelope is present.")
    boolean requireSignature;

    @Option(names = {
            "--expected-key-sha256"}, description = "SHA-256 fingerprint of the public key the evidence must be signed with.")
    String expectedKeySha256;

    @Override
    public Integer call() throws Exception {
        AppManifest manifest = new ManifestLoader().load(app);
        Path signatureFile = evidenceFile.resolveSibling(evidenceFile.getFileName() + ".sig");
        String evidence = Files.readString(evidenceFile);
        String signature = Files.isRegularFile(signatureFile)
                ? Files.readString(signatureFile)
                : null;
        if (signature == null && requireSignature) {
            System.err.println("Evidence signature required but " + signatureFile + " is missing");
            return 1;
        }
        ReleaseEvidenceVerifier.Result result = new ReleaseEvidenceVerifier()
                .verify(manifest, evidence, signature, blankToNull(expectedKeySha256));
        result.mismatches()
                .forEach(mismatch -> System.err.println(
                        mismatch.subject() + ": " + mismatch.reason()));
        if (!result.verified()) {
            System.err.println("Release evidence verification failed: "
                    + result.mismatches().size() + " mismatch(es)");
            return 1;
        }
        System.out.println("Release evidence verified against " + app
                + (signature != null ? " (signature valid)" : ""));
        return 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
