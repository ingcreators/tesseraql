package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.jwt.SignatureVerifier;
import io.tesseraql.yaml.http.HttpOutbound;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The production JWKS fetcher rides the outbound gateway; what stays its own is the protocol —
 * https-pinning, the bounded document, and every failure leaving as the verifier's
 * {@code UNAUTHORIZED} (docs/duplication-consolidation.md, campaign 1).
 */
class GatewayJwksFetcherTest {

    private static final URI JWKS = URI.create("https://idp.example.com/jwks");

    private static OutboundGateway answering(int status, byte[] body, AtomicInteger calls) {
        return new OutboundGateway() {
            @Override
            public Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> call(HttpCallSpec spec, byte[] requestBody,
                    Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RawResponse exchange(HttpCallSpec spec, byte[] requestBody,
                    Map<String, String> headers) {
                calls.incrementAndGet();
                return new RawResponse(status, body, Map.of());
            }
        };
    }

    private static byte[] jwksFor(RSAPublicKey key) {
        String json = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\""
                + base64Url(key.getModulus()) + "\",\"e\":\"" + base64Url(key.getPublicExponent())
                + "\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void fetchesAndParsesTheKeySet() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) gen.generateKeyPair().getPublic();
        AtomicInteger calls = new AtomicInteger();

        Map<String, RSAPublicKey> keys = new GatewayJwksFetcher(
                answering(200, jwksFor(key), calls), Duration.ofSeconds(5)).fetch(JWKS);

        assertThat(keys).containsKey("k1");
        assertThat(keys.get("k1").getModulus()).isEqualTo(key.getModulus());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void plainHttpOffLoopbackIsRefusedBeforeTheGatewayIsTouched() {
        AtomicInteger calls = new AtomicInteger();
        GatewayJwksFetcher fetcher = new GatewayJwksFetcher(
                answering(200, new byte[0], calls), Duration.ofSeconds(5));

        assertThatThrownBy(() -> fetcher.fetch(URI.create("http://idp.example.com/jwks")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("must be https");
        assertThat(calls.get()).isZero();
    }

    @Test
    void aNonOkStatusAnswersUnauthorized() {
        GatewayJwksFetcher fetcher = new GatewayJwksFetcher(
                answering(503, new byte[0], new AtomicInteger()), Duration.ofSeconds(5));

        assertThatThrownBy(() -> fetcher.fetch(JWKS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("HTTP 503")
                .satisfies(ex -> assertThat(((TqlException) ex).code())
                        .isEqualTo(SignatureVerifier.UNAUTHORIZED));
    }

    /**
     * A gateway refusal (denied host, open circuit) maps to the verifier's UNAUTHORIZED — a
     * key set that cannot be fetched means the bearer cannot be verified — but carries the
     * gateway's message, so the operator reads the allow-list fix rather than a bare
     * "unauthorized".
     */
    @Test
    void aGatewayRefusalMapsToUnauthorizedCarryingTheFix() {
        OutboundGateway denying = new OutboundGateway() {
            @Override
            public Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> call(HttpCallSpec spec, byte[] requestBody,
                    Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RawResponse exchange(HttpCallSpec spec, byte[] requestBody,
                    Map<String, String> headers) {
                throw new TqlException(HttpOutbound.HOST_DENIED,
                        "Outbound host 'idp.example.com' is not in"
                                + " tesseraql.http.outbound.allowedHosts");
            }
        };

        assertThatThrownBy(() -> new GatewayJwksFetcher(denying, Duration.ofSeconds(5))
                .fetch(JWKS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("allowedHosts")
                .satisfies(ex -> assertThat(((TqlException) ex).code())
                        .isEqualTo(SignatureVerifier.UNAUTHORIZED));
    }

    @Test
    void anOversizedDocumentIsRefused() {
        byte[] huge = new byte[512 * 1024 + 1];
        GatewayJwksFetcher fetcher = new GatewayJwksFetcher(
                answering(200, huge, new AtomicInteger()), Duration.ofSeconds(5));

        assertThatThrownBy(() -> fetcher.fetch(JWKS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("too large");
    }

    /** Loopback http stays allowed for local development, matching the OIDC client's rule. */
    @Test
    void loopbackHttpIsAllowed() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) gen.generateKeyPair().getPublic();

        assertThat(new GatewayJwksFetcher(answering(200, jwksFor(key), new AtomicInteger()),
                Duration.ofSeconds(5)).fetch(URI.create("http://localhost:8080/jwks")))
                .containsKey("k1");
    }

    /** Wire header names arrive in whatever case the server chose. */
    @Test
    void headersOnTheRawResponseAreCaseInsensitive() {
        OutboundGateway.RawResponse response = new OutboundGateway.RawResponse(200,
                new byte[0], Map.of("Content-Type", List.of("application/json")));
        assertThat(response.header("content-type")).contains("application/json");
    }
}
