package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopbackCallTest {

    private HttpServer server;
    private String base;
    private volatile Headers lastHeaders;
    private volatile String lastMethod;
    private volatile String lastBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastHeaders = exchange.getRequestHeaders();
            lastBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            byte[] body = "answered".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void forwardsTheCredentialHeadersAndTheForm() throws Exception {
        LoopbackCall.Response response = LoopbackCall
                .to("POST", base + "/x", Duration.ofSeconds(5))
                .cookie("tesseraql_sid=s1")
                .csrf("c1")
                .form(LoopbackCall.encode(Map.of("id", "a 1")))
                .send();

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("answered");
        assertThat(response.header("Content-Type")).contains("text/plain");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastHeaders.getFirst("Cookie")).isEqualTo("tesseraql_sid=s1");
        assertThat(lastHeaders.getFirst("X-CSRF-Token")).isEqualTo("c1");
        assertThat(lastHeaders.getFirst("Content-Type"))
                .isEqualTo("application/x-www-form-urlencoded");
        assertThat(lastBody).isEqualTo("id=a+1");
    }

    /** An absent caller header forwards nothing, so a GET without a session stays bare. */
    @Test
    void nullHeadersForwardNothing() throws Exception {
        LoopbackCall.to("GET", base + "/x", Duration.ofSeconds(5))
                .cookie(null).csrf(null).header("HX-Request", null).send();

        assertThat(lastHeaders.containsKey("Cookie")).isFalse();
        assertThat(lastHeaders.containsKey("X-CSRF-Token")).isFalse();
        assertThat(lastBody).isEmpty();
    }

    @Test
    void streamsTheBody() throws Exception {
        LoopbackCall.Streaming streaming = LoopbackCall
                .to("GET", base + "/x", Duration.ofSeconds(5)).stream();

        try (var body = streaming.body()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("answered");
        }
        assertThat(streaming.status()).isEqualTo(200);
    }

    /** The one transport signal: a member that cannot be reached is Unreachable, not an IO leak. */
    @Test
    void aRefusedConnectionIsUnreachable() {
        server.stop(0);
        assertThatThrownBy(() -> LoopbackCall
                .to("GET", base + "/x", Duration.ofSeconds(2)).send())
                .isInstanceOf(LoopbackCall.Unreachable.class);
    }

    @Test
    void encodeSkipsNullsAndEscapes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("a", "1&2");
        params.put("b", null);
        params.put("c", "x y");
        assertThat(LoopbackCall.encode(params)).isEqualTo("a=1%262&c=x+y");
    }
}
