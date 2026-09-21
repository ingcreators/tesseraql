package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code tesseraql bench} against a stub with scripted latencies and refusals
 * (docs/deployment-maturity.md decision 8): concurrency and duration honoured, the three 503
 * codes classified from the body, writes refused without {@code writes: allowed}, {@code --expect}
 * red exiting 3, the JSON shape. The variants — writes by default, expect exiting 1 — are each
 * red on one case.
 */
class BenchCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] CODES = {"TQL-RATE-4293", "TQL-RATE-4294", "TQL-RATE-4295"};

    static HttpServer stub;
    static String base;
    /** Every request the stub answered, and the most it ever held at once. */
    static final AtomicInteger requests = new AtomicInteger();
    static final AtomicInteger inFlight = new AtomicInteger();
    static final AtomicInteger peakInFlight = new AtomicInteger();
    static final AtomicInteger refused = new AtomicInteger();
    static final List<String> idempotencyKeys = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final AtomicInteger posts = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        stub = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        stub.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        stub.createContext("/api/items", exchange -> {
            // The peak is measured over the processing phase only: a worker sends its next
            // request once it holds this answer, which is before this handler has returned, so
            // counting until the return overshoots the worker count on a slow runner.
            int held = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(held, Math::max);
            int n = requests.incrementAndGet();
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (key != null) {
                    idempotencyKeys.add(key);
                }
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            // Every fourth request is refused, the code rotating so each is classified.
            if (n % 4 == 0) {
                String code = CODES[(n / 4) % CODES.length];
                refused.incrementAndGet();
                answer(exchange, 503, "{\"error\":{\"code\":\"" + code
                        + "\",\"message\":\"at capacity\"}}");
            } else {
                answer(exchange, 200, "{\"data\":[]}");
            }
        });
        stub.createContext("/_tesseraql/metrics", exchange -> answer(exchange, 200,
                "# TYPE tesseraql_http_refused_total counter\n"
                        + "tesseraql_http_refused_total{code=\"TQL-RATE-4293\"} "
                        + refused.get() + "\n"
                        + "tesseraql_http_in_flight{kind=\"request\"} 1\n"
                        + "tesseraql_pool_threads_awaiting{pool=\"main\"} 0\n"));
        stub.start();
        base = "http://localhost:" + stub.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** An app with a GET route (one input with a default) and a POST route. */
    private static Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: bench-stub\n");
        Path items = dir.resolve("web/api/items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                input:
                  q:
                    type: string
                    default: widget
                sources:
                  main:
                    sql:
                      file: items.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(items.resolve("items.sql"), "select 1 as id\n");
        Files.writeString(items.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: items.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.createDirectories(dir.resolve("bench"));
        return dir;
    }

    @Test
    void aRouteRunHonoursConcurrencyAndDurationAndClassifiesEveryRefusalCode(@TempDir Path dir)
            throws Exception {
        Path app = app(dir);
        peakInFlight.set(0);
        long started = System.nanoTime();

        Captured run = execute("bench", "--app", app.toString(), "--url", base, "--route",
                "items.list", "--concurrency", "4", "--duration", "1s", "--format", "json");

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(run.exitCode()).as(run.stdout() + run.stderr()).isZero();
        assertThat(elapsedMillis).as("the duration is honoured").isBetween(1_000L, 6_000L);
        assertThat(peakInFlight.get()).as("no more than the workers in flight")
                .isBetween(1, 4);
        JsonNode report = MAPPER.readTree(run.stdout());
        assertThat(report.get("requests").asLong()).isGreaterThan(20);
        assertThat(report.get("mode").asText()).isEqualTo("closed");
        assertThat(report.get("targets").get(0).asText()).isEqualTo("items.list");
        assertThat(report.get("latencyMillis").get("p50").asDouble()).isGreaterThan(0.0);
        assertThat(report.get("latencyMillis").has("p95")).isTrue();
        assertThat(report.get("latencyMillis").has("p99")).isTrue();
        assertThat(report.get("latencyMillis").has("max")).isTrue();
        assertThat(report.get("statuses").get("200").asLong()).isGreaterThan(10);
        assertThat(report.get("statuses").get("503").asLong()).isGreaterThan(0);
        // Classified by the code in the body, never by the status alone.
        assertThat(report.get("refused").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("TQL-RATE-4293", "TQL-RATE-4294", "TQL-RATE-4295");
        assertThat(report.get("refusedPercent").asDouble()).isBetween(15.0, 35.0);
        assertThat(report.get("errors").asLong()).isZero();
        // The scrape before and after, from the stub's own counter.
        assertThat(report.get("scrape").get("refused").get("TQL-RATE-4293").asLong())
                .isGreaterThan(0);
        assertThat(report.get("expect").isNull()).isTrue();
    }

    @Test
    void theTextReportNamesTheBoundBehindEachCode(@TempDir Path dir) throws Exception {
        Captured run = execute("bench", "--app", app(dir).toString(), "--url", base, "--route",
                "items.list", "--concurrency", "2", "--duration", "300ms", "--no-scrape");

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).contains("bench items.list at " + base)
                .contains("2 worker(s), closed loop").contains("latency ms  p50")
                .contains("TQL-RATE-4293").contains("maxInFlight")
                .doesNotContain("scrape");
    }

    @Test
    void aWriteIsRefusedUnlessTheScenarioAllowsItAndThenCarriesAnIdempotencyKey(
            @TempDir Path dir) throws Exception {
        Path app = app(dir);
        Files.writeString(app.resolve("bench/create.yml"), """
                version: tesseraql/v1
                kind: bench
                concurrency: 2
                duration: 300ms
                requests:
                  - route: items.create
                    body: { name: widget }
                """);

        Captured refusedRun = execute("bench", "--app", app.toString(), "--url", base,
                "--scenario", app.resolve("bench/create.yml").toString(), "--no-scrape");
        assertThat(refusedRun.exitCode()).as("a write without the declaration is exit 2")
                .isEqualTo(2);
        assertThat(refusedRun.stderr()).contains("TQL-YAML-1416").contains("writes: allowed");
        assertThat(posts.get()).as("nothing was sent").isZero();

        Files.writeString(app.resolve("bench/create.yml"), """
                version: tesseraql/v1
                kind: bench
                writes: allowed
                concurrency: 2
                duration: 300ms
                requests:
                  - route: items.create
                    body: { name: widget }
                """);
        Captured allowed = execute("bench", "--app", app.toString(), "--url", base,
                "--scenario", app.resolve("bench/create.yml").toString(), "--no-scrape");
        assertThat(allowed.exitCode()).as(allowed.stderr()).isZero();
        assertThat(posts.get()).isGreaterThan(0);
        assertThat(idempotencyKeys).as("one key per write, minted by the harness")
                .hasSize(posts.get());
        assertThat(new java.util.HashSet<>(idempotencyKeys)).hasSize(idempotencyKeys.size());
    }

    @Test
    void aRouteThatIsAWriteNeedsAScenario(@TempDir Path dir) throws Exception {
        Captured run = execute("bench", "--app", app(dir).toString(), "--url", base, "--route",
                "items.create", "--duration", "200ms");

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.stderr()).contains("items.create").contains("POST")
                .contains("writes: allowed");
    }

    @Test
    void anExpectationNotMetExitsThreeAndOneMetExitsZero(@TempDir Path dir) throws Exception {
        Path app = app(dir);

        Captured notMet = execute("bench", "--app", app.toString(), "--url", base, "--route",
                "items.list", "--concurrency", "2", "--duration", "300ms", "--no-scrape",
                "--expect", "refused<1%");
        assertThat(notMet.exitCode()).as("a quarter refused: the gate says no").isEqualTo(3);
        assertThat(notMet.stdout()).contains("refused<1% NOT MET");

        Captured met = execute("bench", "--app", app.toString(), "--url", base, "--route",
                "items.list", "--concurrency", "2", "--duration", "300ms", "--no-scrape",
                "--format", "json", "--expect", "p95<10s,errors<=0");
        assertThat(met.exitCode()).isZero();
        JsonNode expect = MAPPER.readTree(met.stdout()).get("expect");
        assertThat(expect.get("pass").asBoolean()).isTrue();
        assertThat(expect.get("checks")).hasSize(2);

        Captured grammar = execute("bench", "--app", app.toString(), "--url", base, "--route",
                "items.list", "--duration", "200ms", "--no-scrape", "--expect", "p95 under 1s");
        assertThat(grammar.exitCode()).isEqualTo(2);
        assertThat(grammar.stderr()).contains("p95<250ms");
    }

    @Test
    void aScenarioNamingAnUnknownRouteIsRefusedBeforeARequestIsSent(@TempDir Path dir)
            throws Exception {
        Path app = app(dir);
        Files.writeString(app.resolve("bench/typo.yml"), """
                version: tesseraql/v1
                kind: bench
                requests:
                  - route: items.lsit
                """);
        int before = requests.get();

        Captured run = execute("bench", "--app", app.toString(), "--url", base, "--scenario",
                app.resolve("bench/typo.yml").toString(), "--no-scrape");

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.stderr()).contains("TQL-YAML-1414").contains("items.lsit");
        assertThat(requests.get()).isEqualTo(before);
    }

    @Test
    void theTargetsAreChosenOneWay(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        assertThat(execute("bench", "--app", app.toString(), "--url", base).exitCode())
                .isEqualTo(2);
        Files.writeString(app.resolve("bench/browse.yml"), """
                version: tesseraql/v1
                kind: bench
                requests:
                  - route: items.list
                """);
        Captured both = execute("bench", "--app", app.toString(), "--url", base, "--route",
                "items.list", "--scenario", app.resolve("bench/browse.yml").toString());
        assertThat(both.exitCode()).isEqualTo(2);
        assertThat(both.stderr()).contains("Choose one");
    }

    @Test
    void anOpenLoopOffersTheRate(@TempDir Path dir) throws Exception {
        Captured run = execute("bench", "--app", app(dir).toString(), "--url", base, "--route",
                "items.list", "--rate", "50", "--duration", "1s", "--no-scrape", "--format",
                "json");

        assertThat(run.exitCode()).as(run.stderr()).isZero();
        JsonNode report = MAPPER.readTree(run.stdout());
        assertThat(report.get("mode").asText()).isEqualTo("open");
        assertThat(report.get("ratePerSecond").asDouble()).isEqualTo(50.0);
        assertThat(report.get("requests").asLong()).as("about fifty in a second")
                .isBetween(25L, 75L);
    }

    private record Captured(int exitCode, String stdout, String stderr) {
    }

    private static Captured execute(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new TesseraqlCli()).execute(args);
            return new Captured(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
