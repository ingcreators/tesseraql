package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Slice 2 of docs/http-edge.md: the error envelope, and the completion guarantee behind it.
 *
 * <p>Slice 1 measured the happy path, which is the easy half. The design named the hard half in
 * advance and put it outside the list of things to rebuild, because it is not a line item:
 * the unit of work ran {@code addOnCompletion} whether the exchange succeeded or failed, and
 * five places in this framework depend on that — the audit row, the per-route concurrency permit,
 * the lane permit, the telemetry span, the SQL producer's streamed body. Re-implementing that is
 * easy. Noticing every place that relies on it is not, and the failure mode is silent and only on
 * the error path.
 *
 * <p>So the route this drives always fails, and it declares {@code maxInFlight: 1}. A permit that
 * is not given back is invisible on the first request and refuses the second.
 */
@Testcontainers
class HttpEdgeFailurePathIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String PATH = "/api/broken";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        delete(appHome);
    }

    /**
     * A refusal produced off a route is the refusal the route declares.
     *
     * <p>The {@code onException} clauses are the ones the compiler put in the model, reused rather
     * than reimplemented, so this is a check that they were reached — that the failure arrives as
     * the framework's error envelope and not as a bare 500 from something above the pipeline.
     *
     * <p>It used to compare the edge's answer against the answer of the Camel route mounted behind
     * it. That route went with docs/camel-removal.md decision 1, and both halves of the comparison
     * became the same request — so the assertion is on the envelope itself now.
     */
    @Test
    void theErrorEnvelopeIsTheRoutesOwn() {
        HttpResponse<String> failed = get(PATH);

        assertThat(failed.statusCode()).isNotEqualTo(200);
        assertThat(failed.body()).contains("\"code\":\"TQL-");
    }

    /**
     * A permit taken on the failure path is given back.
     *
     * <p>{@code maxInFlight: 1} and a route that always fails: without the completion drain the
     * first request would take the permit and keep it, and the second would be refused with
     * {@code TQL-RATE-4291} instead of the failure it actually has. Three sequential requests is
     * two more than it takes to see that.
     */
    @Test
    void aPermitTakenOnTheFailurePathIsGivenBack() {
        String expected = get(PATH).body();

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> again = get(PATH);
            assertThat(again.body()).doesNotContain("TQL-RATE-4291");
            assertThat(again.body()).isEqualTo(expected);
        }
    }

    private static HttpResponse<String> get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + path)).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-http-edge-failure");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: edge-failure
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path broken = target.resolve("web/api/broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("get.yml"), """
                version: tesseraql/v1
                id: broken
                kind: route
                recipe: query-json
                security:
                  auth: public
                admission:
                  concurrency:
                    maxInFlight: 1
                sources:
                  main:
                    sql:
                      file: broken.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(broken.resolve("broken.sql"),
                "select * from a_table_that_is_not_there\n");
        return target;
    }

    private static void delete(Path target) throws IOException {
        if (target == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(target)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
