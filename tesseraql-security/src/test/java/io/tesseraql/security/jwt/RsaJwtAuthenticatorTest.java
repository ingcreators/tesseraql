package io.tesseraql.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies RS256 bearer tokens against a static configured public key (design ch. 11.1). */
class RsaJwtAuthenticatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();
    }

    private static JwtConfig rs256Config() {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return new JwtConfig("RS256", null, pem, null, null, null, null, "roles", "permissions",
                "groups", "tenant_id", "preferred_username", "name");
    }

    private static String rs256Token(Map<String, Object> claims, PrivateKey signingKey)
            throws Exception {
        return rs256Token(claims, signingKey, "RS256", null);
    }

    private static String rs256Token(Map<String, Object> claims, PrivateKey signingKey, String alg,
            String kid) throws Exception {
        String headerJson = kid == null
                ? "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}"
                : "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String header = ENC.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = ENC.encodeToString(MAPPER.writeValueAsBytes(claims));
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(signingKey);
        rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = ENC.encodeToString(rsa.sign());
        return header + "." + payload + "." + signature;
    }

    @Test
    void acceptsTokenSignedByConfiguredKey() throws Exception {
        String jwt = rs256Token(
                Map.of("sub", "svc-1", "roles", java.util.List.of("SERVICE")),
                keyPair.getPrivate());

        Principal principal = new JwtAuthenticator(rs256Config()).authenticate("Bearer " + jwt);

        assertThat(principal.subject()).isEqualTo("svc-1");
        assertThat(principal.hasRole("SERVICE")).isTrue();
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        String jwt = rs256Token(Map.of("sub", "svc-1"), otherKeyPair.getPrivate());
        assertThatThrownBy(() -> new JwtAuthenticator(rs256Config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        String jwt = rs256Token(Map.of("sub", "svc-1"), keyPair.getPrivate());
        String[] parts = jwt.split("\\.");
        String forged = ENC.encodeToString("{\"sub\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forged + "." + parts[2];
        assertThatThrownBy(
                () -> new JwtAuthenticator(rs256Config()).authenticate("Bearer " + tampered))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsHs256TokenAgainstRs256Config() throws Exception {
        // Algorithm confusion: a token whose header claims HS256 must not validate under an RS256
        // config, even if an attacker tried to use the RSA public key as an HMAC secret.
        String header = ENC.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = ENC.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "svc-1")));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                keyPair.getPublic().getEncoded(), "HmacSHA256"));
        String signature = ENC.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        String jwt = header + "." + payload + "." + signature;

        assertThatThrownBy(() -> new JwtAuthenticator(rs256Config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("alg");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String jwt = rs256Token(Map.of("sub", "svc-1", "exp", 1), keyPair.getPrivate());
        assertThatThrownBy(() -> new JwtAuthenticator(rs256Config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void honorsClockSkewForRecentlyExpiredToken() throws Exception {
        long justExpired = System.currentTimeMillis() / 1000L - 10;
        String jwt = rs256Token(Map.of("sub", "svc-1", "exp", justExpired), keyPair.getPrivate());
        JwtConfig lenient = new JwtConfig("RS256", null, rs256Config().publicKey(), null, null,
                null,
                java.time.Duration.ofMinutes(1), "roles", "permissions", "groups",
                "tenant_id", "preferred_username", "name");

        Principal principal = new JwtAuthenticator(lenient).authenticate("Bearer " + jwt);
        assertThat(principal.subject()).isEqualTo("svc-1");
    }

    @Test
    void acceptsTokenWithKidAgainstStaticKey() throws Exception {
        String jwt = rs256Token(Map.of("sub", "svc-1"), keyPair.getPrivate(), "RS256", "key-2026");
        Principal principal = new JwtAuthenticator(rs256Config()).authenticate("Bearer " + jwt);
        assertThat(principal.subject()).isEqualTo("svc-1");
    }

    @Test
    void buildsKeyFromJwkJson() throws Exception {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        String n = ENC.encodeToString(toUnsigned(pub.getModulus().toByteArray()));
        String e = ENC.encodeToString(toUnsigned(pub.getPublicExponent().toByteArray()));
        String jwk = "{\"kty\":\"RSA\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}";
        JwtConfig jwkConfig = new JwtConfig("RS256", null, jwk, null, null, null, null, "roles",
                "permissions", "groups", "tenant_id", "preferred_username", "name");
        String jwt = rs256Token(Map.of("sub", "svc-1"), keyPair.getPrivate());

        Principal principal = new JwtAuthenticator(jwkConfig).authenticate("Bearer " + jwt);
        assertThat(principal.subject()).isEqualTo("svc-1");
    }

    private static byte[] toUnsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
