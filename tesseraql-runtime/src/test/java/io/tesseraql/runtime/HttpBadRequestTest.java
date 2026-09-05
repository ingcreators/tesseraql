package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The router's typed 400 (docs/http-edge-robustness.md decision 6).
 *
 * <p>No container and no runtime: this handler is a function of a routing context, and every
 * property under test is about what the transport hands it. Driven over raw sockets because the
 * cases are malformed requests, which no HTTP client library will send.
 *
 * <p>All four decoder shapes and both vertx-web failures were reproduced against vertx-core
 * 5.1.6 and netty-codec-http 4.2.17 before the handler was written; these cases pin what was
 * measured.
 */
class HttpBadRequestTest {

    private static final int MAX_FORM_FIELDS = 4;

    static Vertx vertx;
    static HttpServer server;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setMergeFormAttributes(true));
        router.post("/f").handler(ctx -> ctx.response().end("ok"));
        router.get("/g").handler(ctx -> ctx.response().end("ok"));
        // The handler under test, wired the way HttpBadRequest.install wires it. Installing it
        // directly rather than through install() is what keeps this test container-free; the
        // install path itself is exercised by every runtime boot in the suite.
        router.errorHandler(400, ctx -> HttpBadRequest.answer(ctx, MAX_FORM_FIELDS));
        server = vertx.createHttpServer(new HttpServerOptions()
                .setMaxFormFields(MAX_FORM_FIELDS).setMaxFormAttributeSize(1024))
                .requestHandler(router).listen(0)
                .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        port = server.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    /** Too many fields names the key that bounds them. */
    @Test
    void tooManyFormFieldsNamesTheFieldCount() {
        String answer = post("a=1&b=2&c=3&d=4&e=5&f=6&g=7&h=8");

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).contains("TQL-FIELD-2012");
        assertThat(answer).contains("tesseraql.http.maxFormFields");
    }

    /**
     * An over-size field names the body bound, which is where its ceiling comes from.
     *
     * <p>It arrives as a bare {@code java.io.IOException} — the body handler unwraps the
     * decoder's wrapper — so a dispatch on the two named exception types would miss exactly the
     * case this campaign is about.
     */
    @Test
    void anOverSizeFieldNamesTheBodyBound() {
        String answer = post("v=" + "x".repeat(5000));

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).contains("TQL-FIELD-2012");
        assertThat(answer).contains("tesseraql.http.maxBodyBytes");
    }

    /**
     * The buffered bound says what it means, and does not claim the body was too large.
     *
     * <p>{@code TooLongFormFieldException} means the decoder read past its buffer without
     * finding a field delimiter — which is a body that is not a form, not a body over a size.
     */
    @Test
    void theBufferedBoundSaysNoDelimiterWasFoundRatherThanTooLarge() {
        String answer = post("x".repeat(5000));

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).contains("TQL-FIELD-2012");
        assertThat(answer).contains("without finding the end of a field");
        assertThat(answer).doesNotContain("too large");
    }

    /** A body that is not a form at all names no bound, because none was crossed. */
    @Test
    void anUndecodableBodyNamesNoBound() {
        String answer = post("a=%zz");

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).contains("TQL-FIELD-2012");
        assertThat(answer).contains("not valid");
        assertThat(answer).doesNotContain("maxFormFields");
        assertThat(answer).doesNotContain("maxBodyBytes");
    }

    /**
     * An htmx caller gets the fragment the swap allowance reads, not a JSON document.
     *
     * <p>Without the marker the browser shows nothing at all: the bootstrap declines to swap a
     * 4xx whose body carries no allowance marker.
     */
    @Test
    void anHtmxCallerGetsTheFieldErrorsFragment() {
        String answer = post("a=1&b=2&c=3&d=4&e=5&f=6", "HX-Request: true\r\n");

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).contains("text/html");
        assertThat(answer).contains("data-hc-field-errors");
        assertThat(answer).contains("hc-alert");
        assertThat(answer).contains("TQL-FIELD-2012");
    }

    /**
     * The two-character reproducer for every hazard this handler has to survive.
     *
     * <p>{@code GET /%zz} fails during route matching rather than through a {@code fail(int)}
     * call, so the handler runs with a null failure, a status code of {@code -1}, and a
     * normalized path that throws the same exception that caused the failure. A handler reading
     * any of those three answers the reason phrase instead — or is swallowed as "Error in error
     * handler" and answers it anyway.
     */
    @Test
    void aPathTheRouterCannotNormalizeIsNotThisHandlersToName() {
        String answer = raw("GET /%zz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 400");
        // vertx-web's own answer, untouched: this failure is not the decoder's to explain.
        assertThat(answer).doesNotContain("TQL-FIELD-2012");
    }

    /** vertx-web raises its own 400s, and they must not be answered with form vocabulary. */
    @Test
    void aMissingHostHeaderIsNotAnsweredAsAFormProblem() {
        String answer = raw("GET /g HTTP/1.1\r\nConnection: close\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).doesNotContain("TQL-FIELD-2012");
        assertThat(answer).doesNotContain("form");
    }

    /** A well-formed request is untouched by any of this. */
    @Test
    void anOrdinaryFormIsUnaffected() {
        String answer = post("a=1&b=2");

        assertThat(answer).startsWith("HTTP/1.1 200");
        assertThat(answer).contains("ok");
    }

    private static String post(String body) {
        return post(body, "");
    }

    private static String post(String body, String extraHeaders) {
        return raw("POST /f HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + extraHeaders
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n" + body);
    }

    private static String raw(String request) {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder answer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                answer.append(line).append('\n');
            }
            return answer.toString();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
