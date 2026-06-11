package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the declarative route runner against a lightweight in-JVM HTTP stub, so it needs no
 * database or Camel runtime.
 */
class RouteTestRunnerTest {

    static HttpServer server;
    static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ping", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/secure", exchange -> respond(exchange, 401, "{\"error\":\"no\"}"));
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void runsRouteCasesAndReportsResults() {
        RouteSuite suite = RouteTestRunner.parse("""
                tests:
                  - name: ping ok
                    method: GET
                    path: /ping
                    expect:
                      status: 200
                      bodyContains:
                        - "\\"ok\\":true"
                  - name: secure rejected
                    path: /secure
                    expect:
                      status: 401
                  - name: wrong status expectation
                    path: /ping
                    expect:
                      status: 500
                """);

        TestReport report = new RouteTestRunner(baseUrl).run(suite);

        assertThat(report.results()).hasSize(3);
        assertThat(report.results().get(0).passed()).isTrue();
        assertThat(report.results().get(1).passed()).isTrue();
        assertThat(report.results().get(2).passed()).isFalse();
        assertThat(report.passed()).isEqualTo(2);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
