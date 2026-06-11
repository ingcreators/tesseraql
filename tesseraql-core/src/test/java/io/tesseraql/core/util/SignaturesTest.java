package io.tesseraql.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignaturesTest {

    private static final byte[] PAYLOAD = "evidence payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void signAndVerifyRoundTrip() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        String signature = Signatures.sign(PAYLOAD, keys.privateKey());
        assertThat(Signatures.verify(PAYLOAD, signature, keys.publicKey())).isTrue();
    }

    @Test
    void tamperedPayloadAndWrongKeyFailVerification() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        Signatures.GeneratedKeyPair other = Signatures.generateKeyPair();
        String signature = Signatures.sign(PAYLOAD, keys.privateKey());

        byte[] tampered = "evidence payload!".getBytes(StandardCharsets.UTF_8);
        assertThat(Signatures.verify(tampered, signature, keys.publicKey())).isFalse();
        assertThat(Signatures.verify(PAYLOAD, signature, other.publicKey())).isFalse();
        assertThat(Signatures.verify(PAYLOAD, "not-base64!", keys.publicKey())).isFalse();
    }

    @Test
    void acceptsPemArmoredKeys() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        String pemPrivate = "-----BEGIN PRIVATE KEY-----\n" + keys.privateKey()
                + "\n-----END PRIVATE KEY-----\n";
        String pemPublic = "-----BEGIN PUBLIC KEY-----\n" + keys.publicKey()
                + "\n-----END PUBLIC KEY-----\n";
        String signature = Signatures.sign(PAYLOAD, pemPrivate);
        assertThat(Signatures.verify(PAYLOAD, signature, pemPublic)).isTrue();
    }

    @Test
    void fingerprintIsStableAndSafeToLog() {
        Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();
        assertThat(Signatures.fingerprint(keys.publicKey()))
                .hasSize(64)
                .isEqualTo(Signatures.fingerprint(
                        "-----BEGIN PUBLIC KEY-----\n" + keys.publicKey()
                                + "\n-----END PUBLIC KEY-----"));
    }
}
