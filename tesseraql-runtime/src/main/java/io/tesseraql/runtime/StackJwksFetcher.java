package io.tesseraql.runtime;

import io.tesseraql.oauth.SigningKeys;
import io.tesseraql.security.jwt.JwksFetcher;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@link JwksFetcher} for a runtime whose validation block the stack issuer derived
 * (docs/token-issuance.md decision 9): the published keys are read straight from the shared
 * framework database — the same rows the surface's {@code /_tesseraql/oauth/jwks} renders —
 * never fetched over HTTP. The HTTP document exists for external clients; for a member it was
 * a hairpin through its own front door, and once JWKS fetches rode the outbound gateway
 * (docs/duplication-consolidation.md campaign 1) it put every bearer validation behind the
 * member's egress allow list: a stack whose members had not allow-listed their own origin
 * failed every validation closed, silently — the 2026-08-24 Codex acceptance's first finding.
 * The posture is {@link LoopbackCall}'s: the stack reaching itself is not egress, and the
 * allow list answers a different question.
 *
 * <p>The retired-key horizon is a generous constant rather than the surface's configured
 * access-token lifetime, which a member does not know. The horizon only decides how long a
 * <em>retired</em> key stays visible; a token signed by one still carries the {@code exp} the
 * validator enforces, so listing retired keys longer can only accept tokens the surface
 * really signed.
 */
final class StackJwksFetcher implements JwksFetcher {

    /** Retired keys stay visible this long past retirement (see the class note). */
    private static final Duration RETIRED_KEY_HORIZON = Duration.ofDays(7);

    private final SigningKeys keys;

    StackJwksFetcher(javax.sql.DataSource frameworkDataSource) {
        this.keys = new SigningKeys(frameworkDataSource, Clock.systemUTC());
    }

    @Override
    public Map<String, RSAPublicKey> fetch(URI jwksUri) {
        Map<String, RSAPublicKey> published = new LinkedHashMap<>();
        for (SigningKeys.SigningKey key : keys.published(RETIRED_KEY_HORIZON)) {
            published.put(key.kid(), SigningKeys.publicKey(key));
        }
        return published;
    }
}
