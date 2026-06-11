package io.tesseraql.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JwtAuthenticatorTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JwtConfig config() {
        return new JwtConfig(SECRET, null, "roles", "permissions", "groups",
                "tenant_id", "preferred_username", "name");
    }

    private static String token(Map<String, Object> claims) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    @Test
    void parsesClaimsIntoPrincipal() throws Exception {
        String jwt = token(Map.of(
                "sub", "u001",
                "preferred_username", "sato",
                "tenant_id", "tenant-a",
                "roles", java.util.List.of("USER_READ"),
                "permissions", java.util.List.of("users:read")));

        Principal principal = new JwtAuthenticator(config()).authenticate("Bearer " + jwt);

        assertThat(principal.subject()).isEqualTo("u001");
        assertThat(principal.loginId()).isEqualTo("sato");
        assertThat(principal.tenantId()).isEqualTo("tenant-a");
        assertThat(principal.hasRole("USER_READ")).isTrue();
        assertThat(principal.hasPermission("users:read")).isTrue();
        assertThat(principal.claim().get("tenant_id")).isEqualTo("tenant-a");
    }

    @Test
    void rejectsMissingBearer() {
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate(null))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Basic abc"))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        String jwt = token(Map.of("sub", "u001"));
        String tampered = jwt.substring(0, jwt.length() - 2) + "xy";

        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + tampered))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String jwt = token(Map.of("sub", "u001", "exp", 1));
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class);
    }
}
