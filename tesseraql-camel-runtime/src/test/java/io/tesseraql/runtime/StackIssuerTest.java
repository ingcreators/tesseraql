package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The derived stack-issuer block (docs/token-issuance.md decision 9): RS256 against the
 * surface's JWKS, the origin as issuer, audiences derived from addresses, and the two
 * refusals — no origin, and a key source declared beside the stack's issuer.
 */
class StackIssuerTest {

    private static final String ORIGIN = "https://stack.example.com";

    @Test
    void enablementReadsTheStackFilesSecuritySubtree() {
        assertThat(StackIssuer.enabled(null)).isFalse();
        assertThat(StackIssuer.enabled(Map.of())).isFalse();
        assertThat(StackIssuer.enabled(Map.of("oauth", Map.of("enabled", "true")))).isTrue();
        assertThat(StackIssuer.enabled(Map.of("oauth", Map.of("enabled", true)))).isTrue();
        assertThat(StackIssuer.enabled(Map.of("oauth", Map.of("enabled", "false")))).isFalse();
    }

    @Test
    void theDerivedBlockIsRs256AgainstTheOriginsDocument() {
        Map<String, Object> jwt = StackIssuer.jwt(ORIGIN, Map.of());

        assertThat(jwt)
                .containsEntry("algorithm", "RS256")
                .containsEntry("jwksUri", ORIGIN + "/_tesseraql/oauth/jwks")
                .containsEntry("issuer", ORIGIN)
                .containsEntry("rolesClaim", "roles")
                .containsEntry("permissionsClaim", "permissions");
    }

    @Test
    void declaredClaimNamesOutrankTheDefaults() {
        Map<String, Object> jwt = StackIssuer.jwt(ORIGIN, Map.of("jwt",
                Map.of("rolesClaim", "authorities", "loginClaim", "preferred_username")));

        assertThat(jwt)
                .containsEntry("rolesClaim", "authorities")
                .containsEntry("loginClaim", "preferred_username")
                .containsEntry("permissionsClaim", "permissions");
    }

    @Test
    void anIssuerWithoutAnOriginIsRefused() {
        assertThatThrownBy(() -> StackIssuer.jwt(null, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("externalOrigin");
    }

    @Test
    void aRuntimesAudienceIsItsAddressPlusTheStackOrigin() {
        AppConfig applied = StackIssuer.apply(config(Map.of()),
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/shop", "this test");

        assertThat(audience(applied)).containsExactly(ORIGIN + "/shop", ORIGIN);
        assertThat(applied.getString("tesseraql.security.jwt.algorithm")).contains("RS256");
    }

    @Test
    void aDeclaredAudienceSurvivesTheGraftAndTheOriginJoinsIt() {
        Map<String, Object> root = config(
                Map.of("security", Map.of("jwt", Map.of("audience", "urn:shop")))).root();
        AppConfig applied = StackIssuer.apply(new AppConfig(root),
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/shop", "this test");

        assertThat(audience(applied)).containsExactly("urn:shop", ORIGIN);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> audience(AppConfig applied) {
        return (java.util.List<String>) applied.navigate("tesseraql.security.jwt.audience");
    }

    @Test
    void aDeclaredKeySourceIsASecondIssuerAndRefused() {
        AppConfig withSecret = config(
                Map.of("security", Map.of("jwt", Map.of("secret", "0123456789abcdef"))));

        assertThatThrownBy(() -> StackIssuer.apply(withSecret,
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/shop", "member 'shop'"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("second issuer")
                .hasMessageContaining("member 'shop'");
    }

    private static AppConfig config(Map<String, Object> tesseraql) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tesseraql", deepMutable(tesseraql));
        return new AppConfig(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutable(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key,
                value instanceof Map ? deepMutable((Map<String, Object>) value) : value));
        return copy;
    }
}
