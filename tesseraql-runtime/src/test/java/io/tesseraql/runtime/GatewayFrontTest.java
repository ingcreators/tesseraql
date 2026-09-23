package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Vertx;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The development gateway's front before its relay exists (docs/host-development.md decision 7).
 * {@code dev} binds first so its members are given the port the socket got; a request that
 * arrives while they boot is told to come back rather than refused at the socket, and from the
 * moment the relay is served every request is the relay's.
 */
class GatewayFrontTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void answersComeBackLaterUntilTheRelayIsServedAndThenRelays() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            MultiAppGateway.Front front = new MultiAppGateway.Front();
            int port = vertx.createHttpServer().requestHandler(front).listen(0)
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .actualPort();

            HttpResponse<String> starting = get(port);
            assertThat(starting.statusCode()).as("booting, not refused").isEqualTo(503);
            assertThat(starting.headers().firstValue("Retry-After")).contains("1");

            front.serve(request -> request.response().end("relayed"));
            HttpResponse<String> served = get(port);
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo("relayed");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static HttpResponse<String> get(int port) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/x"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
