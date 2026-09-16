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
    void aRuntimesAudienceIsItsAddressPlusTheStackOriginPlusItsMcpResource() {
        AppConfig applied = StackIssuer.apply(config(Map.of()),
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/shop", "this test");

        assertThat(audience(applied)).containsExactly(ORIGIN + "/shop", ORIGIN,
                ORIGIN + "/shop/_tesseraql/mcp");
        assertThat(applied.getString("tesseraql.security.jwt.algorithm")).contains("RS256");
    }

    @Test
    void aDeclaredAudienceSurvivesAndTheIssuersVocabularyJoinsIt() {
        Map<String, Object> root = config(
                Map.of("security", Map.of("jwt", Map.of("audience", "urn:shop")))).root();
        AppConfig applied = StackIssuer.apply(new AppConfig(root),
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/shop", "this test");

        // The address, the origin, and the member's MCP resource always join: they are how the
        // stack's mints name this member, the whole stack, and the member's MCP surface,
        // whatever the application declared for itself.
        assertThat(audience(applied)).containsExactly("urn:shop", ORIGIN + "/shop", ORIGIN,
                ORIGIN + "/shop/_tesseraql/mcp");
    }

    /**
     * Under the stack issuer the MCP resource is the address-derived name, full stop: the RFC
     * 9728 document, the challenge and the grants all name that one, so a declared
     * {@code tesseraql.mcp.resource} was a name no client was told and no token could carry —
     * the gate refused every token, silently (docs/audit-low-leads.md, G6). It is refused at
     * boot instead, the way a declared key source is.
     */
    @Test
    void aDeclaredMcpResourceUnderTheStackIssuerIsRefused() {
        AppConfig withOverride = config(Map.of("mcp", Map.of("resource", "urn:shop:mcp")));

        assertThatThrownBy(() -> StackIssuer.apply(withOverride,
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/shop", "member 'shop'"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-OAUTH-3005")
                .hasMessageContaining("tesseraql.mcp.resource")
                .hasMessageContaining("member 'shop'");
    }

    /**
     * A member named in Japanese is addressed on the wire percent-encoded, and its MCP resource
     * — a URI — is spelled the way the stack's document publishes it and a grant carries it
     * (docs/audit-low-leads.md, unfiled 48). The address itself stays the catalogue's spelling.
     */
    @Test
    void aJapaneseMembersMcpResourceIsSpelledAsTheWireSpellsIt() {
        AppConfig applied = StackIssuer.apply(config(Map.of()),
                StackIssuer.jwt(ORIGIN, Map.of()), ORIGIN, "/受注", "this test");

        assertThat(audience(applied)).containsExactly(ORIGIN + "/受注", ORIGIN,
                ORIGIN + "/%E5%8F%97%E6%B3%A8/_tesseraql/mcp");
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
