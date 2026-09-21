package io.tesseraql.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The load harness behind {@code tesseraql bench} (docs/deployment-maturity.md decision 8):
 * JDK-only — {@link HttpClient} on virtual threads — closed-loop by default ({@code concurrency}
 * workers each sending the next request as soon as the last one answered), open-loop with a
 * {@code rate}. It keeps every sample and reports exact percentiles, and it classifies a
 * refusal by the {@code TQL-RATE} code in its body rather than by its status, because a
 * monitor that cannot tell the runtime's in-flight bound from a route's own limiter cannot tell
 * which number to raise.
 */
final class BenchHarness {

    /** One request shape, resolved against the manifest. */
    record Target(String routeId, String method, String path, Map<String, String> query,
            String body, boolean write, int weight) {

        /** The path with its query string, encoded. */
        String pathWithQuery() {
            if (query.isEmpty()) {
                return path;
            }
            StringBuilder out = new StringBuilder(path).append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    out.append('&');
                }
                out.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }
            return out.toString();
        }

        private static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    /** The run's shape: the closed loop's workers or the open loop's rate, and the clock. */
    record Shape(int concurrency, Duration duration, Duration rampUp, Double rate) {

        boolean openLoop() {
            return rate != null && rate > 0;
        }
    }

    /** What one request answered. */
    private record Answer(long nanos, int status, String code, String error) {
    }

    /** The run's outcome: every latency, the status mix, the refusals by code, the errors. */
    record Result(Instant startedAt, Duration elapsed, long requests, long[] sortedNanos,
            Map<Integer, Long> statuses, Map<String, Long> refused, long errors,
            String firstError) {

        double throughputPerSecond() {
            double seconds = elapsed.toNanos() / 1_000_000_000.0;
            return seconds <= 0 ? 0.0 : requests / seconds;
        }

        double percentileMillis(double quantile) {
            return percentile(sortedNanos, quantile) / 1_000_000.0;
        }

        double maxMillis() {
            return sortedNanos.length == 0
                    ? 0.0
                    : sortedNanos[sortedNanos.length - 1] / 1_000_000.0;
        }

        long refusedTotal() {
            return refused.values().stream().mapToLong(Long::longValue).sum();
        }

        double refusedPercent() {
            return requests == 0 ? 0.0 : refusedTotal() * 100.0 / requests;
        }

        double errorsPercent() {
            long attempted = requests + errors;
            return attempted == 0 ? 0.0 : errors * 100.0 / attempted;
        }
    }

    private BenchHarness() {
    }

    /** The nearest-rank percentile of sorted samples: exact, never interpolated from buckets. */
    static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) Math.ceil(quantile * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    /** An {@link HttpClient} for the run: HTTP/1.1, virtual threads, a bounded connect. */
    static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /** Drives {@code targets} at {@code base} for the shape's duration and collects the answers. */
    static Result run(HttpClient client, String base, List<Target> targets, Shape shape,
            String bearer) throws InterruptedException {
        int[] cumulative = new int[targets.size()];
        int total = 0;
        for (int i = 0; i < targets.size(); i++) {
            total += Math.max(1, targets.get(i).weight());
            cumulative[i] = total;
        }
        int totalWeight = total;
        ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        long deadline = startNanos + shape.duration().toNanos();
        Runnable one = () -> {
            Target target = targets.size() == 1
                    ? targets.get(0)
                    : pick(targets, cumulative,
                            totalWeight);
            answers.add(send(client, base, target, bearer));
        };
        if (shape.openLoop()) {
            openLoop(one, shape, deadline);
        } else {
            closedLoop(one, shape, deadline);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        return collect(startedAt, elapsed, answers);
    }

    private static Target pick(List<Target> targets, int[] cumulative, int totalWeight) {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return targets.get(i);
            }
        }
        return targets.get(targets.size() - 1);
    }

    /** Each worker sends the next request as soon as the last one answered, until the deadline. */
    private static void closedLoop(Runnable one, Shape shape, long deadline)
            throws InterruptedException {
        List<Thread> workers = new ArrayList<>();
        long rampNanos = shape.rampUp() == null ? 0L : shape.rampUp().toNanos();
        for (int i = 0; i < shape.concurrency(); i++) {
            long startAfter = shape.concurrency() <= 1 ? 0L : rampNanos * i / shape.concurrency();
            workers.add(Thread.ofVirtual().name("tql-bench-" + i).start(() -> {
                sleepNanos(startAfter);
                while (System.nanoTime() < deadline) {
                    one.run();
                }
            }));
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    /**
     * Offers requests at the rate whatever the answers do — the load a queue of callers offers —
     * with the concurrency as the bound on requests in flight: past it the offer waits, which is
     * what the caller's queue would do too.
     */
    private static void openLoop(Runnable one, Shape shape, long deadline)
            throws InterruptedException {
        long interval = (long) (1_000_000_000.0 / shape.rate());
        Semaphore inFlight = new Semaphore(Math.max(1, shape.concurrency()));
        List<Thread> sent = new ArrayList<>();
        long next = System.nanoTime();
        while (System.nanoTime() < deadline) {
            inFlight.acquire();
            sent.add(Thread.ofVirtual().start(() -> {
                try {
                    one.run();
                } finally {
                    inFlight.release();
                }
            }));
            next += interval;
            sleepNanos(next - System.nanoTime());
        }
        for (Thread thread : sent) {
            thread.join();
        }
    }

    private static void sleepNanos(long nanos) {
        if (nanos > 0) {
            try {
                Thread.sleep(Duration.ofNanos(nanos));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"(TQL-[A-Z]+-\\d+)\"");

    /** Sends one request and classifies its answer; an exception is an error, never a throw. */
    private static Answer send(HttpClient client, String base, Target target, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(base + target.pathWithQuery()))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (target.write()) {
            request.method(target.method(), HttpRequest.BodyPublishers.ofString(
                    target.body() == null ? "{}" : target.body()))
                    .header("Content-Type", "application/json")
                    // Minted per request: a write the harness repeats is a write the runtime
                    // must be allowed to run again (docs/idempotency-key.md).
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        } else {
            request.method(target.method(), HttpRequest.BodyPublishers.noBody());
        }
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            long nanos = System.nanoTime() - started;
            String code = null;
            if (response.statusCode() == 503 || response.statusCode() == 429) {
                Matcher matcher = CODE.matcher(response.body() == null ? "" : response.body());
                if (matcher.find() && matcher.group(1).startsWith("TQL-RATE-")) {
                    code = matcher.group(1);
                }
            }
            return new Answer(nanos, response.statusCode(), code, null);
        } catch (IOException | RuntimeException failed) {
            return new Answer(System.nanoTime() - started, 0, null,
                    failed.getClass().getSimpleName() + ": " + failed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Answer(System.nanoTime() - started, 0, null, "interrupted");
        }
    }

    private static Result collect(Instant startedAt, Duration elapsed,
            ConcurrentLinkedQueue<Answer> answers) {
        long[] latencies = new long[answers.size()];
        int count = 0;
        Map<Integer, Long> statuses = new TreeMap<>();
        Map<String, Long> refused = new TreeMap<>();
        long errors = 0;
        String firstError = null;
        for (Answer answer : answers) {
            if (answer.error() != null) {
                errors++;
                if (firstError == null) {
                    firstError = answer.error();
                }
                continue;
            }
            latencies[count++] = answer.nanos();
            statuses.merge(answer.status(), 1L, Long::sum);
            if (answer.code() != null) {
                refused.merge(answer.code(), 1L, Long::sum);
            }
        }
        long[] sorted = Arrays.copyOf(latencies, count);
        Arrays.sort(sorted);
        return new Result(startedAt, elapsed, count, sorted, statuses, refused, errors,
                firstError);
    }

    // ------------------------------------------------------------------ expectations

    /** One threshold, and how the run measured against it. */
    record Check(String expression, double actual, boolean pass) {
    }

    private static final Pattern EXPECTATION = Pattern.compile(
            "\\s*(p50|p95|p99|max|refused|errors|throughput|requests)\\s*(<=|>=|<|>)\\s*"
                    + "(\\d+(?:\\.\\d+)?)\\s*(ms|s|%|rps)?\\s*");

    /**
     * Evaluates {@code --expect}: comma-separated {@code metric op value[unit]} — latencies in
     * {@code ms} (or {@code s}), {@code refused} and {@code errors} as a {@code %} of requests or a
     * count, {@code throughput} in requests per second, {@code requests} a count.
     *
     * @throws IllegalArgumentException on a clause the grammar does not admit
     */
    static List<Check> evaluate(String expectations, Result result) {
        List<Check> checks = new ArrayList<>();
        for (String clause : expectations.split(",")) {
            if (clause.isBlank()) {
                continue;
            }
            Matcher matcher = EXPECTATION.matcher(clause);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("'" + clause.trim() + "' is not an expectation:"
                        + " write <metric><op><value>, e.g. p95<250ms, refused<1%,"
                        + " errors<=0, throughput>500 (metrics: p50, p95, p99, max, refused,"
                        + " errors, throughput, requests)");
            }
            String metric = matcher.group(1);
            String op = matcher.group(2);
            double value = Double.parseDouble(matcher.group(3));
            String unit = matcher.group(4) == null ? "" : matcher.group(4);
            double actual;
            switch (metric) {
                case "p50", "p95", "p99", "max" -> {
                    if ("s".equals(unit)) {
                        value *= 1000.0;
                    } else if (!unit.isEmpty() && !"ms".equals(unit)) {
                        throw new IllegalArgumentException("'" + clause.trim()
                                + "': a latency is in ms or s");
                    }
                    actual = "max".equals(metric)
                            ? result.maxMillis()
                            : result.percentileMillis(quantileOf(metric));
                }
                case "refused" -> actual = "%".equals(unit)
                        ? result.refusedPercent()
                        : result.refusedTotal();
                case "errors" -> actual = "%".equals(unit)
                        ? result.errorsPercent()
                        : result.errors();
                case "throughput" -> actual = result.throughputPerSecond();
                default -> actual = result.requests();
            }
            boolean pass = switch (op) {
                case "<" -> actual < value;
                case "<=" -> actual <= value;
                case ">" -> actual > value;
                default -> actual >= value;
            };
            checks.add(new Check(clause.trim().replace(" ", ""), actual, pass));
        }
        return checks;
    }

    private static double quantileOf(String metric) {
        return switch (metric) {
            case "p50" -> 0.50;
            case "p95" -> 0.95;
            default -> 0.99;
        };
    }

    // ------------------------------------------------------------------ the scrape readout

    /** One scrape, as {@code family{labels}} to value. */
    record Scrape(Map<String, Double> samples) {

        /** Parses the text exposition; comments and blank lines are skipped. */
        static Scrape parse(String text) {
            Map<String, Double> samples = new LinkedHashMap<>();
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int space = trimmed.lastIndexOf(' ');
                if (space <= 0) {
                    continue;
                }
                try {
                    samples.put(trimmed.substring(0, space),
                            Double.parseDouble(trimmed.substring(space + 1)));
                } catch (NumberFormatException notASample) {
                    // A timestamped or malformed line; the readout is best effort.
                }
            }
            return new Scrape(samples);
        }

        /** The sum of the samples whose name starts with {@code prefix}. */
        double sum(String prefix) {
            double total = 0;
            for (Map.Entry<String, Double> sample : samples.entrySet()) {
                if (sample.getKey().startsWith(prefix)) {
                    total += sample.getValue();
                }
            }
            return total;
        }

        /** The samples whose name starts with {@code prefix}, by name. */
        Map<String, Double> matching(String prefix) {
            Map<String, Double> out = new TreeMap<>();
            samples.forEach((name, value) -> {
                if (name.startsWith(prefix)) {
                    out.put(name, value);
                }
            });
            return out;
        }
    }

    /** The capacity signals' movement across the run, as a JSON-shaped map (decision 7). */
    static Map<String, Object> readout(Scrape before, Scrape after) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> refusedByCode = new LinkedHashMap<>();
        after.matching("tesseraql_http_refused_total{").forEach((name, value) -> {
            Matcher code = Pattern.compile("code=\"([^\"]+)\"").matcher(name);
            String label = code.find() ? code.group(1) : name;
            refusedByCode.put(label,
                    (long) (value - before.samples().getOrDefault(name, 0.0)));
        });
        out.put("refused", refusedByCode);
        out.put("laneRejected", (long) (after.sum("tesseraql_lane_rejected_total")
                - before.sum("tesseraql_lane_rejected_total")));
        out.put("routeErrors", (long) (after.sum("tesseraql_route_errors_total")
                - before.sum("tesseraql_route_errors_total")));
        out.put("inFlightAfter",
                (long) after.sum("tesseraql_http_in_flight{kind=\"request\"}"));
        double awaiting = 0;
        for (double value : after.matching("tesseraql_pool_threads_awaiting").values()) {
            awaiting = Math.max(awaiting, value);
        }
        out.put("poolAwaitingAfter", (long) awaiting);
        return out;
    }

    /** The readout on one line, for the text report. */
    @SuppressWarnings("unchecked")
    static String readoutLine(Map<String, Object> readout) {
        Map<String, Object> refused = (Map<String, Object>) readout.get("refused");
        long refusedTotal = refused.values().stream().mapToLong(v -> (Long) v).sum();
        StringBuilder line = new StringBuilder("refused +").append(refusedTotal);
        if (!refused.isEmpty()) {
            line.append(" (");
            boolean first = true;
            for (Map.Entry<String, Object> entry : refused.entrySet()) {
                line.append(first ? "" : ", ").append(entry.getKey()).append(" +")
                        .append(entry.getValue());
                first = false;
            }
            line.append(')');
        }
        return line.append(" · lane rejected +").append(readout.get("laneRejected"))
                .append(" · route errors +").append(readout.get("routeErrors"))
                .append(" · after the run: in flight ").append(readout.get("inFlightAfter"))
                .append(", pool awaiting ").append(readout.get("poolAwaitingAfter"))
                .toString();
    }

    /** What each refusal code means, for the report's reader. */
    static String gloss(String code) {
        return switch (code) {
            case "TQL-RATE-4293" -> "the runtime's in-flight bound (tesseraql.http.maxInFlight)";
            case "TQL-RATE-4295" ->
                "the runtime's event-stream bound (tesseraql.http.maxEventStreams)";
            case "TQL-RATE-4294" -> "the front door's per-member share of forwards";
            case "TQL-RATE-4296" -> "the front door's per-member share of stream forwards";
            case "TQL-RATE-4291" -> "the route's own rate limit";
            case "TQL-RATE-4292" -> "the sign-in throttle";
            default -> "a rate refusal";
        };
    }

    /** A number with one decimal, the same on every locale. */
    static String tenths(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
