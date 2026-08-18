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
    private static final String AUDIENCE = "https://app.example.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JwtConfig config() {
        return config(java.util.List.of(AUDIENCE), null);
    }

    private static JwtConfig config(java.util.List<String> audience, Boolean requireExpiration) {
        return new JwtConfig("HS256", SECRET, null, null, null, null, audience, null,
                requireExpiration, "roles", "permissions", "groups", "tenant_id",
                "preferred_username", "name");
    }

    private static String token(Map<String, Object> claims) throws Exception {
        return token(claims, "HS256");
    }

    /**
     * Mints a token that is valid apart from what the case under test changes.
     *
     * <p>{@code exp} and {@code aud} are supplied here rather than in every case because a token
     * without them is no longer a valid token: an absent {@code exp} never expires, and an absent
     * {@code aud} is not bound to this application. A case that means to omit or contradict either
     * passes it explicitly, and its value wins.
     */
    private static String token(Map<String, Object> claims, String alg) throws Exception {
        Map<String, Object> payloadClaims = new java.util.LinkedHashMap<>();
        payloadClaims.put("exp", System.currentTimeMillis() / 1000L + 3600);
        payloadClaims.put("aud", AUDIENCE);
        payloadClaims.putAll(claims);
        payloadClaims.values().removeIf(java.util.Objects::isNull);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(
                ("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(payloadClaims));
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
        // The signature's FIRST character, changed to a DIFFERENT one: six leading bits of the
        // decoded signature are guaranteed to move. Overwriting the trailing characters looked
        // equivalent and was not — the last base64url character of a 32-byte signature carries
        // only two effective bits (the decoder ignores the rest), so about one minted token in
        // a thousand kept verifying and the test flaked instead of failing.
        int signature = jwt.lastIndexOf('.') + 1;
        char first = jwt.charAt(signature);
        String tampered = jwt.substring(0, signature) + (first == 'A' ? 'B' : 'A')
                + jwt.substring(signature + 1);

        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + tampered))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String jwt = token(Map.of("sub", "u001", "exp", 1));
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsExpiredTokenWhoseExpIsAString() throws Exception {
        // Some IdPs and hand-rolled mints emit exp as a string; it must still be enforced, not
        // silently skipped (which made a string-exp token immortal).
        String jwt = token(Map.of("sub", "u001", "exp", "1"));
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void acceptsAValidStringExp() throws Exception {
        long future = System.currentTimeMillis() / 1000L + 3600;
        String jwt = token(Map.of("sub", "u001", "exp", Long.toString(future)));
        assertThat(new JwtAuthenticator(config()).authenticate("Bearer " + jwt).subject())
                .isEqualTo("u001");
    }

    @Test
    void rejectsANonNumericExp() throws Exception {
        String jwt = token(Map.of("sub", "u001", "exp", "not-a-number"));
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("numeric");
    }

    @Test
    void rejectsAlgNone() throws Exception {
        // A token claiming "none" must never validate against an HS256 config, even though its
        // (ignored) signature segment is correctly computed: the alg is bound from config.
        String jwt = token(Map.of("sub", "u001"), "none");
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("alg");
    }

    @Test
    void rejectsAlgConfusionAgainstHs256Config() throws Exception {
        // A token forged with header alg=RS256 must not slip past an HS256-configured verifier.
        String jwt = token(Map.of("sub", "u001"), "RS256");
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("alg");
    }

    // --- audience (docs/audit-hardening.md Decision 1) -------------------------------------------

    /**
     * The confused deputy this slice closes: a correctly signed, unexpired token the same issuer
     * minted for a different relying party.
     */
    @Test
    void rejectsATokenMintedForAnotherRelyingParty() throws Exception {
        String jwt = token(Map.of("sub", "u001", "aud", "https://other.example.com"));
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4143")
                .hasMessageContaining("audience");
    }

    /** {@code aud} is string-or-array in the token; the array form matches on any element. */
    @Test
    void acceptsAnArrayAudienceContainingThisApplication() throws Exception {
        String jwt = token(Map.of("sub", "u001",
                "aud", java.util.List.of("https://other.example.com", AUDIENCE)));
        assertThat(new JwtAuthenticator(config()).authenticate("Bearer " + jwt).subject())
                .isEqualTo("u001");
    }

    @Test
    void rejectsAnArrayAudienceNamingOnlyOtherRelyingParties() throws Exception {
        String jwt = token(Map.of("sub", "u001",
                "aud",
                java.util.List.of("https://other.example.com", "https://third.example.com")));
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("audience");
    }

    /** {@code audience} is a list, so an application may answer to more than one identifier. */
    @Test
    void acceptsAnyDeclaredAudience() throws Exception {
        JwtConfig twoNames = config(java.util.List.of("https://legacy.example.com", AUDIENCE),
                null);
        String jwt = token(Map.of("sub", "u001", "aud", "https://legacy.example.com"));
        assertThat(new JwtAuthenticator(twoNames).authenticate("Bearer " + jwt).subject())
                .isEqualTo("u001");
    }

    /** A token with no {@code aud} at all is not bound to anything. */
    @Test
    void rejectsATokenWithNoAudienceClaim() throws Exception {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "u001");
        claims.put("aud", null);
        String jwt = token(claims);
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("audience");
    }

    /**
     * An empty audience skips the check, which no application configuration can reach — TQL-SEC-4048
     * refuses the build and the boot. It stays reachable for internally-built configurations, and
     * this pins that it means "no check" rather than "match nothing".
     */
    @Test
    void anEmptyAudienceChecksNothing() throws Exception {
        String jwt = token(Map.of("sub", "u001", "aud", "https://anyone.example.com"));
        assertThat(new JwtAuthenticator(config(java.util.List.of(), null))
                .authenticate("Bearer " + jwt).subject()).isEqualTo("u001");
    }

    // --- expiry ---------------------------------------------------------------------------------

    /**
     * An absent {@code exp} used to mean "no expiry to check", which is the opposite of what it
     * means: the token never expires.
     */
    @Test
    void rejectsATokenWithNoExpiry() throws Exception {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "u001");
        claims.put("exp", null);
        String jwt = token(claims);
        assertThatThrownBy(() -> new JwtAuthenticator(config()).authenticate("Bearer " + jwt))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4144")
                .hasMessageContaining("never expire");
    }

    @Test
    void acceptsATokenWithNoExpiryWhenTheConfigurationSaysSo() throws Exception {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "u001");
        claims.put("exp", null);
        String jwt = token(claims);
        assertThat(new JwtAuthenticator(config(java.util.List.of(AUDIENCE), false))
                .authenticate("Bearer " + jwt).subject()).isEqualTo("u001");
    }

    /**
     * The trap the design recorded: {@code requireExpiration} is a {@code Boolean} because this
     * record applies every default by null-check, so a primitive could not tell a caller passing
     * {@code false} from a caller meaning "unset" — and the zero value is the unsafe one.
     */
    @Test
    void anUnsetRequireExpirationDefaultsToRequiring() {
        assertThat(config(java.util.List.of(AUDIENCE), null).requireExpiration()).isTrue();
        assertThat(config(java.util.List.of(AUDIENCE), false).requireExpiration()).isFalse();
    }
}
