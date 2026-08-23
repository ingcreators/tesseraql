package io.tesseraql.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The production {@link AccessTokenSigner} (docs/token-issuance.md decision 3): RS256 over the
 * stack's database-held key set, {@code kid} in the header so a validator picks the right
 * published key during a rotation overlap. JDK-only — the JOSE machinery on the classpath is
 * CXF's own affair, not this signer's.
 */
public final class Rs256TokenSigner implements AccessTokenSigner {

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final SigningKeys keys;

    public Rs256TokenSigner(SigningKeys keys) {
        this.keys = keys;
    }

    @Override
    public String sign(Map<String, Object> claims) {
        SigningKeys.SigningKey key = keys.ensureActive();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", key.kid());
        String signingInput = encode(header) + "." + encode(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(SigningKeys.privateKey(key));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RS256 signing failed", e);
        }
    }

    private static String encode(Map<String, Object> json) {
        try {
            return URL.encodeToString(MAPPER.writeValueAsBytes(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Claims are not serializable", e);
        }
    }
}
