package io.tesseraql.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.policy.PolicyEngine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies and parses HS256 bearer JWTs into a {@link Principal} (design ch. 11.1 {@code bearer}).
 *
 * <p>This is the first authentication method; the design's RS256/JWKS, OIDC, SAML, API key, and
 * mTLS methods plug in behind the same authentication step in later phases.
 */
public final class JwtAuthenticator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final JwtConfig config;

    public JwtAuthenticator(JwtConfig config) {
        this.config = config;
    }

    /** Authenticates the value of an HTTP {@code Authorization} header. */
    public Principal authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Missing bearer token");
        }
        return parse(authorizationHeader.substring("Bearer ".length()).trim());
    }

    @SuppressWarnings("unchecked")
    private Principal parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Malformed JWT");
        }
        verifySignature(parts);

        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(URL_DECODER.decode(parts[1]), Map.class);
        } catch (Exception ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid JWT payload");
        }
        validateClaims(claims);
        return toPrincipal(claims);
    }

    private void verifySignature(String[] parts) {
        if (config.secret() == null || config.secret().isBlank()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] actual = URL_DECODER.decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid JWT signature");
            }
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT signature verification failed");
        }
    }

    private void validateClaims(Map<String, Object> claims) {
        Object exp = claims.get("exp");
        if (exp instanceof Number expSeconds
                && System.currentTimeMillis() / 1000L >= expSeconds.longValue()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT has expired");
        }
        if (config.issuer() != null && !config.issuer().equals(claims.get("iss"))) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT issuer mismatch");
        }
    }

    private Principal toPrincipal(Map<String, Object> claims) {
        return new Principal(
                asString(claims.get("sub")),
                asString(claims.get(config.loginClaim())),
                asString(claims.get(config.nameClaim())),
                asString(claims.get(config.tenantClaim())),
                asStringList(claims.get(config.groupsClaim())),
                asStringList(claims.get(config.rolesClaim())),
                asStringList(claims.get(config.permissionsClaim())),
                claims);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(String.valueOf(element)));
            return result;
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string.split("\\s+"));
        }
        return List.of();
    }
}
