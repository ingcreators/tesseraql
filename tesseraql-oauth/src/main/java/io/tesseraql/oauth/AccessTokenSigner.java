package io.tesseraql.oauth;

import java.util.Map;

/**
 * The seam between grant handling and key material (docs/token-issuance.md decision 3): the
 * provider assembles an access token's claims and this signs them into the compact wire form.
 * The production implementation signs RS256 with the stack's database-held key set and arrives
 * with the signing-key slice; until then tests observe the claims through a capturing fake.
 * Claim naming is aligned with {@code SessionTokens} when the issuer-unification slice makes
 * the two mints one (decision 9).
 */
public interface AccessTokenSigner {

    /** Signs the assembled claims; the return value is the access token as the client sees it. */
    String sign(Map<String, Object> claims);
}
