package io.tesseraql.yaml.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.util.Signatures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EvidenceSignatureTest {

    @Test
    void signedEnvelopeRoundTripsThroughJsonAndVerifies() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        byte[] evidence = "{\"evidenceVersion\":\"tesseraql/evidence/v1\"}"
                .getBytes(StandardCharsets.UTF_8);

        EvidenceSignature signed = EvidenceSignature.sign(
                evidence, keys.privateKey(), keys.publicKey());
        EvidenceSignature parsed = EvidenceSignature.parse(signed.toJson());

        assertThat(parsed.algorithm()).isEqualTo("Ed25519");
        assertThat(parsed.publicKeySha256()).isEqualTo(Signatures.fingerprint(keys.publicKey()));
        assertThat(parsed.verifies(evidence)).isTrue();
    }

    @Test
    void editedEvidenceFailsVerification() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        byte[] evidence = "{\"files\":{}}".getBytes(StandardCharsets.UTF_8);
        EvidenceSignature signed = EvidenceSignature.sign(
                evidence, keys.privateKey(), keys.publicKey());

        byte[] edited = "{\"files\":{\"x\":1}}".getBytes(StandardCharsets.UTF_8);
        assertThat(signed.verifies(edited)).isFalse();
    }

    @Test
    void envelopeNeverContainsThePrivateKey() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        byte[] evidence = "{}".getBytes(StandardCharsets.UTF_8);
        String json = EvidenceSignature.sign(evidence, keys.privateKey(), keys.publicKey())
                .toJson();
        assertThat(json).doesNotContain(keys.privateKey());
    }
}
