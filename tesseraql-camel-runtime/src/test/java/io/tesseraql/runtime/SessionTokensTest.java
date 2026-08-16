package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The provider behind the console's issue-token page (docs/suite-architecture.md Decision 20).
 *
 * <p>Two properties matter here and neither is visible from the page: an application that does not
 * issue answers rather than fails, and the provider mints for the ambient principal only — the one
 * the request binder seeds from the authenticated exchange — so a route cannot ask it for a token
 * belonging to somebody else.
 */
class SessionTokensTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JwtConfig JWT = new JwtConfig("HS256", "unit-test-secret", null, null,
            null, "https://issuer.example.com", List.of("https://app.example.com"), null, null,
            null, null, null, null, null, null);

    @Test
    void anApplicationThatDoesNotIssueSaysSoInsteadOfFailing() {
        SessionTokens tokens = new SessionTokens(JWT, Duration.ofMinutes(15), "15m", false);

        assertThat(tokens.status()).containsEntry("enabled", false).containsEntry("ttl", "15m");
        // Not an exception: the page renders this as the configuration key to set. A refusal here
        // would reach the operator as a 500 with nothing actionable in it.
        assertThat(tokens.issue(Map.of("principal", Map.of("subject", "alice"))))
                .containsEntry("enabled", false)
                .doesNotContainKey("token");
    }

    @Test
    void mintsForTheAmbientPrincipalAndCarriesItsClaims() throws Exception {
        SessionTokens tokens = new SessionTokens(JWT, Duration.ofMinutes(15), "15m", true);

        Map<String, Object> issued = tokens.issue(Map.of(
                "principal", Map.of("subject", "alice", "loginId", "alice",
                        "tenantId", "t-1", "roles", List.of("USER_READ"),
                        "permissions", List.of("users.read"), "groups", List.of("staff")),
                "displayName", "Alice"));

        assertThat(issued).containsEntry("enabled", true).containsEntry("tokenType", "Bearer");
        var claims = MAPPER.readTree(new String(Base64.getUrlDecoder().decode(
                String.valueOf(issued.get("token")).split("\\.")[1]), StandardCharsets.UTF_8));
        assertThat(claims.path("sub").asText()).isEqualTo("alice");
        assertThat(claims.path("name").asText()).isEqualTo("Alice");
        assertThat(claims.path("roles").toString()).contains("USER_READ");
        assertThat(claims.path("permissions").toString()).contains("users.read");
        assertThat(claims.path("groups").toString()).contains("staff");
        // Required since the audience work, and minted without it the token would be signed
        // correctly and refused on arrival by the application that issued it.
        assertThat(claims.path("aud").asText()).isEqualTo("https://app.example.com");
        assertThat(claims.path("iss").asText()).isEqualTo("https://issuer.example.com");
    }

    /**
     * A route that wired {@code subject: 'admin'} would be writing a parameter this provider never
     * reads: the only identity it accepts is the ambient one, and without it there is nothing to
     * mint for.
     */
    @Test
    void refusesToMintWhenNoAuthenticatedPrincipalReachedIt() {
        SessionTokens tokens = new SessionTokens(JWT, Duration.ofMinutes(15), "15m", true);

        assertThatThrownBy(() -> tokens.issue(Map.of("subject", "admin",
                "roles", List.of("SUPERUSER"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authenticated principal");
    }
}
