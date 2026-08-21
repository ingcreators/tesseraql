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
 * The provider behind the console's issue-token page (docs/stack-architecture.md Decision 20).
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

    // The member axis (docs/token-issuance.md decision 9): naming a member narrows to that
    // member's active view under the browser's own entry rules, per token.

    private static SessionTokens memberAware() {
        return new SessionTokens(JWT, Duration.ofMinutes(15), "15m", true, null,
                new java.util.LinkedHashMap<>(Map.of("shop", "/shop")),
                "https://stack.example.com");
    }

    private static io.tesseraql.security.Principal holder(
            io.tesseraql.security.Principal.RoleGrant... grants) {
        return new io.tesseraql.security.Principal("u-1", "eve", null, null, List.of(),
                List.of("staff"), List.of("read"), Map.of(), List.of(grants), List.of());
    }

    @Test
    void oneHeldRoleAutoActivatesForTheNamedMember() {
        var principal = holder(
                new io.tesseraql.security.Principal.RoleGrant("staff", null, List.of("read")),
                new io.tesseraql.security.Principal.RoleGrant("approver", "shop",
                        List.of("shop.approve")));

        var narrowed = memberAware().narrowed(principal, "shop", null);

        assertThat(narrowed.roles()).contains("approver");
        assertThat(narrowed.permissions()).contains("shop.approve");
    }

    @Test
    void severalHeldRolesStayInactiveUnlessSelected() {
        var principal = holder(
                new io.tesseraql.security.Principal.RoleGrant("staff", null, List.of("read")),
                new io.tesseraql.security.Principal.RoleGrant("approver", "shop", List.of()),
                new io.tesseraql.security.Principal.RoleGrant("auditor", "shop", List.of()));

        var narrowed = memberAware().narrowed(principal, "shop", null);
        assertThat(narrowed.roles()).containsExactly("staff");

        var selected = memberAware().narrowed(principal, "shop", "auditor");
        assertThat(selected.roles()).contains("auditor").doesNotContain("approver");
    }

    @Test
    void aRoleNotHeldForTheNamedMemberRefuses() {
        var principal = holder(
                new io.tesseraql.security.Principal.RoleGrant("approver", "billing",
                        List.of()));

        assertThatThrownBy(() -> memberAware().narrowed(principal, "shop", "approver"))
                .hasMessageContaining("for member 'shop'");
    }

    @Test
    void anUnknownMemberRefusesNamingTheStack() {
        assertThatThrownBy(() -> memberAware().narrowed(holder(), "nope", null))
                .hasMessageContaining("members are");
        assertThatThrownBy(() -> new SessionTokens(JWT, Duration.ofMinutes(15), "15m", true)
                .narrowed(holder(), "shop", null))
                .hasMessageContaining("addresses no stack members");
    }

    @Test
    void aMemberScopedMintCarriesTheMembersAddressAsAudience() throws Exception {
        var minted = memberAware().mint(holder(
                new io.tesseraql.security.Principal.RoleGrant("approver", "shop",
                        List.of())),
                "shop");

        String token = String.valueOf(minted.get("token"));
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertThat(new ObjectMapper().readTree(payload).get("aud").asText())
                .isEqualTo("https://stack.example.com/shop");
    }
}
