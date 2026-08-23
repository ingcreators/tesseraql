package io.tesseraql.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import org.junit.jupiter.api.Test;

/**
 * The post-login target is total on any cookie value. The {@code tql_oidc_next} cookie is the
 * caller's to set, and the callback resolves it <em>after</em> the ID token validated — a decode
 * that throws there would render a 500 onto a response already carrying the freshly issued
 * session cookie, and a malformed escape planted in a victim's browser would fail every login
 * they attempt until the cookie is cleared. A value the decoder or the sanitizer refuses falls
 * back to the configured default, like an absent cookie.
 */
class OidcPostLoginTargetTest {

    private static OidcRoutes routes() {
        return new OidcRoutes(
                new OidcConfig(null, "client", null, null, null, "/home", null, null, false,
                        false, null),
                null, null, null, null, null, null, null);
    }

    private static Exchange withCookie(String cookieHeader) {
        Exchange exchange = new Exchange(Beans.NONE);
        if (cookieHeader != null) {
            exchange.request().header("Cookie", cookieHeader);
        }
        return exchange;
    }

    @Test
    void aCarriedNextIsDecodedAndUsed() {
        assertThat(routes().postLoginTarget(withCookie("tql_oidc_next=%2Forders%3Ftab%3Dopen")))
                .isEqualTo("/orders?tab=open");
    }

    @Test
    void aMalformedEscapeFallsBackToTheDefaultTarget() {
        assertThat(routes().postLoginTarget(withCookie("tql_oidc_next=%zz")))
                .isEqualTo("/home");
    }

    @Test
    void anAbsentCookieFallsBackToTheDefaultTarget() {
        assertThat(routes().postLoginTarget(withCookie(null))).isEqualTo("/home");
    }

    @Test
    void anOffsiteTargetIsRefusedBySanitizing() {
        assertThat(routes().postLoginTarget(
                withCookie("tql_oidc_next=" + java.net.URLEncoder.encode("https://evil.example/",
                        java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo("/home");
    }
}
