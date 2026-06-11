package io.tesseraql.yaml.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.util.Signatures;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ReleaseEvidenceVerifierTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void freshEvidenceVerifiesAgainstItsOwnSources() {
        AppManifest manifest = exampleApp();
        String evidence = new ReleaseEvidence().toJson(manifest, "user-admin", "1.0.0");
        ReleaseEvidenceVerifier.Result result =
                new ReleaseEvidenceVerifier().verify(manifest, evidence, null, null);
        assertThat(result.verified()).isTrue();
    }

    @Test
    void recordedHashDriftIsReportedPerFile() {
        AppManifest manifest = exampleApp();
        String evidence = new ReleaseEvidence().toJson(manifest, "user-admin", "1.0.0");
        // Flip one recorded hash inside the files section.
        int files = evidence.indexOf("\"files\"");
        String tampered = evidence.substring(0, files) + evidence.substring(files)
                .replaceFirst("\"[0-9a-f]{64}\"", "\"" + "0".repeat(64) + "\"");

        ReleaseEvidenceVerifier.Result result =
                new ReleaseEvidenceVerifier().verify(manifest, tampered, null, null);

        assertThat(result.verified()).isFalse();
        assertThat(result.mismatches())
                .anySatisfy(m -> assertThat(m.reason()).contains("content changed"));
    }

    @Test
    void signatureIsVerifiedAndPinnedToTheExpectedKey() {
        AppManifest manifest = exampleApp();
        String evidence = new ReleaseEvidence().toJson(manifest, "user-admin", "1.0.0");
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        String signature = EvidenceSignature.sign(evidence.getBytes(StandardCharsets.UTF_8),
                keys.privateKey(), keys.publicKey()).toJson();
        ReleaseEvidenceVerifier verifier = new ReleaseEvidenceVerifier();

        assertThat(verifier.verify(manifest, evidence, signature,
                Signatures.fingerprint(keys.publicKey())).verified()).isTrue();

        ReleaseEvidenceVerifier.Result wrongKey = verifier.verify(manifest, evidence, signature,
                "0".repeat(64));
        assertThat(wrongKey.verified()).isFalse();
        assertThat(wrongKey.mismatches())
                .anySatisfy(m -> assertThat(m.reason()).contains("was expected"));

        ReleaseEvidenceVerifier.Result unsigned = verifier.verify(manifest, evidence, null,
                Signatures.fingerprint(keys.publicKey()));
        assertThat(unsigned.verified()).isFalse();
        assertThat(unsigned.mismatches())
                .anySatisfy(m -> assertThat(m.reason()).contains("unsigned"));
    }
}
