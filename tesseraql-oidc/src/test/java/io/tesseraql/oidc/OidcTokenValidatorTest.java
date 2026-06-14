package io.tesseraql.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** ID-token validation: signature/iss via JwtAuthenticator, plus the OIDC aud and nonce checks. */
class OidcTokenValidatorTest {

    private static final String CLIENT_ID = "my-app";
    private static final String ISSUER = "https://idp.example.com/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

    private static KeyPair keyPair;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    /** A validator whose JwtAuthenticator pins the test public key (no JWKS fetch). */
    private static OidcTokenValidator validator() {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        JwtConfig jwt = new JwtConfig("RS256", null, pem, null, null, ISSUER, null,
                "roles", "permissions", "groups", "tenant_id", "preferred_username", "name");
        return new OidcTokenValidator(CLIENT_ID, new JwtAuthenticator(jwt));
    }

    private static String idToken(Map<String, Object> claims) throws Exception {
        String header = ENC.encodeToString(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = ENC.encodeToString(MAPPER.writeValueAsBytes(claims));
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(keyPair.getPrivate());
        rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + ENC.encodeToString(rsa.sign());
    }

    private static Map<String, Object> claims(Object aud, String nonce) {
        long future = System.currentTimeMillis() / 1000L + 600;
        return Map.of("sub", "u-1", "iss", ISSUER, "aud", aud, "nonce", nonce, "exp", future,
                "preferred_username", "alice", "roles", List.of("USER_READ"));
    }

    @Test
    void acceptsValidIdToken() throws Exception {
        Principal principal = validator()
                .validate(idToken(claims(CLIENT_ID, "n-123")), "n-123");
        assertThat(principal.subject()).isEqualTo("u-1");
        assertThat(principal.loginId()).isEqualTo("alice");
        assertThat(principal.hasRole("USER_READ")).isTrue();
    }

    @Test
    void acceptsAudienceArrayContainingClientId() throws Exception {
        Principal principal = validator()
                .validate(idToken(claims(List.of("other", CLIENT_ID), "n-1")), "n-1");
        assertThat(principal.subject()).isEqualTo("u-1");
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        assertThatThrownBy(
                () -> validator().validate(idToken(claims("someone-else", "n-1")), "n-1"))
                .isInstanceOf(OidcException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void rejectsNonceMismatch() throws Exception {
        assertThatThrownBy(
                () -> validator().validate(idToken(claims(CLIENT_ID, "n-actual")), "n-expected"))
                .isInstanceOf(OidcException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        Map<String, Object> wrongIssuer = new java.util.HashMap<>(claims(CLIENT_ID, "n-1"));
        wrongIssuer.put("iss", "https://evil.example.com/");
        assertThatThrownBy(() -> validator().validate(idToken(wrongIssuer), "n-1"))
                .isInstanceOf(OidcException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Map<String, Object> expired = new java.util.HashMap<>(claims(CLIENT_ID, "n-1"));
        expired.put("exp", 1);
        assertThatThrownBy(() -> validator().validate(idToken(expired), "n-1"))
                .isInstanceOf(OidcException.class);
    }
}
