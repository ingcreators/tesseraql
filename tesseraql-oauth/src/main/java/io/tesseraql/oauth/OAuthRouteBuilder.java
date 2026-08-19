package io.tesseraql.oauth;

import java.time.Duration;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The authorization server's HTTP surface, growing slice by slice (docs/token-issuance.md).
 * This slice serves the JWKS: public by design — it is the published half of the key set —
 * and identical from every replica, because the keys live in the framework datasource.
 */
final class OAuthRouteBuilder extends RouteBuilder {

    private final SigningKeys keys;
    private final Duration accessTokenLifetime;

    OAuthRouteBuilder(SigningKeys keys, Duration accessTokenLifetime) {
        this.keys = keys;
        this.accessTokenLifetime = accessTokenLifetime;
    }

    @Override
    public void configure() {
        rest().get("/_tesseraql/oauth/jwks").to("direct:tql.oauth.jwks");
        from("direct:tql.oauth.jwks").routeId("system.oauth.jwks").process(this::jwks);
    }

    private void jwks(Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(JwksDocuments.render(keys.published(accessTokenLifetime)));
    }
}
