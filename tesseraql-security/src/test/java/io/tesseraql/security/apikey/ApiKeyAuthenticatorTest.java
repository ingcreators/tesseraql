package io.tesseraql.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.apikey.ApiKeyConfig.ApiKeyClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** API-key authentication for service callers: hashed keys, deny-by-default, principal mapping. */
class ApiKeyAuthenticatorTest {

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static ApiKeyAuthenticator authenticator() throws Exception {
        ApiKeyClient billing = new ApiKeyClient(sha256Hex("raw-key-123"), "svc:billing", "tenant-a",
                List.of("SERVICE"), List.of("invoices:write"), true);
        ApiKeyClient defaultsSubject = new ApiKeyClient(sha256Hex("plain-key"), null, null,
                List.of(), List.of(), true);
        ApiKeyClient disabled = new ApiKeyClient(sha256Hex("disabled-key"), null, null,
                List.of(), List.of(), false);
        return new ApiKeyAuthenticator(new ApiKeyConfig(null, Map.of(
                "billing-service", billing, "plain", defaultsSubject, "old", disabled)));
    }

    @Test
    void defaultsHeaderToXApiKey() throws Exception {
        assertThat(authenticator().header()).isEqualTo("X-API-Key");
    }

    @Test
    void mapsValidKeyToPrincipal() throws Exception {
        Principal principal = authenticator().authenticate("raw-key-123");

        assertThat(principal.subject()).isEqualTo("svc:billing");
        assertThat(principal.loginId()).isEqualTo("billing-service");
        assertThat(principal.tenantId()).isEqualTo("tenant-a");
        assertThat(principal.hasRole("SERVICE")).isTrue();
        assertThat(principal.hasPermission("invoices:write")).isTrue();
        assertThat(principal.claim().get("api_key_client")).isEqualTo("billing-service");
    }

    @Test
    void defaultsSubjectToClientId() throws Exception {
        Principal principal = authenticator().authenticate("plain-key");
        assertThat(principal.subject()).isEqualTo("plain");
    }

    @Test
    void rejectsInvalidKey() throws Exception {
        assertThatThrownBy(() -> authenticator().authenticate("not-a-real-key"))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsDisabledClientKey() throws Exception {
        assertThatThrownBy(() -> authenticator().authenticate("disabled-key"))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsMissingKey() throws Exception {
        ApiKeyAuthenticator auth = authenticator();
        assertThatThrownBy(() -> auth.authenticate(null)).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> auth.authenticate("  ")).isInstanceOf(TqlException.class);
    }

    @Test
    void storesOnlyHashNotRawKey() throws Exception {
        // The configured secret is a hash, not the raw key — the raw key never appears in config.
        String hash = sha256Hex("raw-key-123");
        assertThat(hash).isNotEqualTo("raw-key-123").hasSize(64);
        // A client carrying only the hash still authenticates the matching raw key.
        ApiKeyAuthenticator auth = new ApiKeyAuthenticator(new ApiKeyConfig(null, Map.of(
                "c", new ApiKeyClient(hash, null, null, List.of(), List.of(), true))));
        assertThat(auth.authenticate("raw-key-123").subject()).isEqualTo("c");
    }
}
