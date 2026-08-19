package io.tesseraql.oauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observes the claims the provider assembles; the RS256 signer arrives with the key slice. */
final class CapturingSigner implements AccessTokenSigner {

    final List<Map<String, Object>> signed = new ArrayList<>();

    @Override
    public String sign(Map<String, Object> claims) {
        signed.add(new LinkedHashMap<>(claims));
        return "signed-" + signed.size();
    }

    Map<String, Object> lastClaims() {
        return signed.get(signed.size() - 1);
    }
}
