package io.tesseraql.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Discovery parses the provider metadata and memoizes the fetch. */
class OidcDiscoveryTest {

    private static final String DOC = """
            {
              "issuer": "https://idp.example.com/",
              "authorization_endpoint": "https://idp.example.com/authorize",
              "token_endpoint": "https://idp.example.com/token",
              "jwks_uri": "https://idp.example.com/jwks",
              "end_session_endpoint": "https://idp.example.com/logout"
            }
            """;

    @Test
    void parsesAndMemoizesMetadata() {
        AtomicInteger calls = new AtomicInteger();
        OidcDiscovery discovery = new OidcDiscovery(
                URI.create("https://idp.example.com/.well-known"),
                uri -> {
                    calls.incrementAndGet();
                    return DOC.getBytes(StandardCharsets.UTF_8);
                });

        OidcMetadata metadata = discovery.metadata();
        assertThat(metadata.issuer()).isEqualTo("https://idp.example.com/");
        assertThat(metadata.authorizationEndpoint())
                .isEqualTo(URI.create("https://idp.example.com/authorize"));
        assertThat(metadata.tokenEndpoint()).isEqualTo(URI.create("https://idp.example.com/token"));
        assertThat(metadata.jwksUri()).isEqualTo(URI.create("https://idp.example.com/jwks"));
        assertThat(metadata.endSessionEndpoint())
                .isEqualTo(URI.create("https://idp.example.com/logout"));

        discovery.metadata();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void allowsAbsentEndSessionEndpoint() {
        OidcDiscovery discovery = new OidcDiscovery(URI.create("https://idp/.well-known"),
                uri -> ("""
                        {"issuer":"https://idp/","authorization_endpoint":"https://idp/a",
                         "token_endpoint":"https://idp/t","jwks_uri":"https://idp/jwks"}
                        """).getBytes(StandardCharsets.UTF_8));
        assertThat(discovery.metadata().endSessionEndpoint()).isNull();
    }

    @Test
    void rejectsMissingRequiredField() {
        OidcDiscovery discovery = new OidcDiscovery(URI.create("https://idp/.well-known"),
                uri -> ("{\"issuer\":\"https://idp/\","
                        + "\"authorization_endpoint\":\"https://idp/a\"}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(discovery::metadata)
                .isInstanceOf(OidcException.class)
                .hasMessageContaining("token_endpoint");
    }
}
