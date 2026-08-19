package io.tesseraql.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.jwt.Jwks;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The published document parses with the member-side machinery: {@link Jwks} is exactly the
 * parser a stack member's bearer validation uses, so this round trip is the interop the
 * issuer-unification slice will rely on.
 */
class JwksRoundTripTest {

    @Test
    void aRenderedKeySetParsesBackToTheSameKeys() {
        SigningKeys.SigningKey first = SigningKeys.generate("initial", Instant.now());
        SigningKeys.SigningKey second = SigningKeys.generate("k-abc123", Instant.now());

        String document = JwksDocuments.render(List.of(first, second));
        Map<String, RSAPublicKey> parsed = Jwks
                .parseJwkSet(document.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed).containsOnlyKeys("initial", "k-abc123");
        assertThat(parsed.get("initial")).isEqualTo(SigningKeys.publicKey(first));
        assertThat(parsed.get("k-abc123")).isEqualTo(SigningKeys.publicKey(second));
    }

    @Test
    void thePrivateHalfNeverEntersTheDocument() {
        SigningKeys.SigningKey key = SigningKeys.generate("initial", Instant.now());

        String document = JwksDocuments.render(List.of(key));

        assertThat(document).doesNotContain("\"d\"").doesNotContain(key.privateKey());
    }
}
