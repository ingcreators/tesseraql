package io.tesseraql.security.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordVerifierTest {

    @Test
    void encodesAndVerifiesPbkdf2() {
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        String hash = encoder.encode("s3cret");
        PasswordVerifier verifier = new PasswordVerifier();

        assertThat(verifier.verify("s3cret", hash, "pbkdf2", encoder.defaultParams())).isTrue();
        assertThat(verifier.verify("wrong", hash, "pbkdf2", encoder.defaultParams())).isFalse();
    }

    @Test
    void defaultsAlgoToPbkdf2() {
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        String hash = encoder.encode("p@ss");
        assertThat(new PasswordVerifier().verify("p@ss", hash, null, null)).isTrue();
    }

    @Test
    void rejectsNulls() {
        assertThat(new PasswordVerifier().verify(null, "x", "pbkdf2", null)).isFalse();
    }
}
