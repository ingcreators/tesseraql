package io.tesseraql.cli;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.bench.BenchScenario;
import io.tesseraql.yaml.bench.BenchScenarios;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.InputField;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code tesseraql bench --app <dir> --url <base-url>}: drives an application's declared routes
 * with load and answers with percentiles, the status mix and the refusals classified by their
 * {@code TQL-RATE} code (docs/deployment-maturity.md decision 8; the method is
 * docs/capacity.md). The targets come from the manifest — a route filled from its declared
 * inputs' defaults, or a {@code kind: bench} scenario — so the harness knows the routes, the
 * inputs, the token exchange and the refusal codes, which is what wrk, k6 or Gatling would each
 * have to be taught.
 *
 * <p>Writes are refused unless the scenario says {@code writes: allowed}; a load run that mutates
 * data is a decision the author writes down, never a flag typed in a hurry. {@code --expect}
 * turns thresholds into exit code 3, the code that already means "a policy gate said no". With a
 * token holding {@code ops.metrics.view} (or an unauthenticated scrape), the capacity signals are
 * read before and after the run and their movement is printed beside the percentiles, so the
 * tuning readout is in the same terminal as the load.
 */
@Command(name = "bench", description = "Drive an application's declared routes with load and report percentiles and refusals by code.")
final class BenchCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    @Option(names = {
            "--app"}, required = true, description = "Path to the app home; the routes and their inputs come from its manifest.")
    Path app;

    @Option(names = {"--url"}, required = true, paramLabel = "<base-url>", description = "Base URL"
            + " of the running application, including the base path if it has one (/<name> on a"
            + " stack).")
    String url;

    @Option(names = {"--route"}, paramLabel = "<id>", description = "A GET route to drive, filled"
            + " from its declared inputs' defaults; repeatable. A write needs a scenario.")
    List<String> routes = new ArrayList<>();

    @Option(names = {"--scenario"}, paramLabel = "<file>", description = "A kind: bench scenario"
            + " (bench/<name>.yml): the requests, their weights and the run's shape.")
    Path scenario;

    @Option(names = {"--concurrency"}, paramLabel = "<n>", description = "Workers in the closed"
            + " loop; with --rate, the bound on requests in flight (default 10, or the"
            + " scenario's).")
    Integer concurrency;

    @Option(names = {"--duration"}, paramLabel = "<duration>", description = "How long to run,"
            + " e.g. 30s or 2m (default 30s, or the scenario's).")
    String duration;

    @Option(names = {"--ramp-up"}, paramLabel = "<duration>", description = "Start the workers"
            + " spread across this span (default none, or the scenario's).")
    String rampUp;

    @Option(names = {"--rate"}, paramLabel = "<per-second>", description = "Open loop: offer this"
            + " many requests per second instead of the closed loop's workers.")
    Double rate;

    @Option(names = {"--token-file"}, paramLabel = "<file>", description = "A file holding the"
            + " bearer token (else TESSERAQL_TOKEN); a token holding ops.metrics.view also"
            + " reads the scrape before and after the run.")
    Path tokenFile;

    @Option(names = {"--login"}, paramLabel = "<id>", description = "Sign in as this login and"
            + " exchange for a token, as token --url does; the password comes from"
            + " TESSERAQL_PASSWORD or a prompt.")
    String login;

    @Option(names = {
            "--format"}, defaultValue = "text", description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    Format format;

    @Option(names = {"--expect"}, paramLabel = "<checks>", description = "Thresholds,"
            + " comma-separated (p95<250ms, refused<1%%, errors<=0, throughput>500); one not"
            + " met exits 3.")
    String expect;

    @Option(names = {"--no-scrape"}, description = "Do not read /_tesseraql/metrics before and"
            + " after the run.")
    boolean noScrape;

    enum Format {
        text, json
    }

    @Mixin
    ConfigOptions configOptions;

    @Mixin
    CompileOptions compile;

    /** A refusal before any request is sent: one sentence on stderr, exit 2. */
    private static final class Refused extends RuntimeException {

        Refused(String message) {
            super(message);
        }
    }

    @Override
    public Integer call() throws Exception {
        configOptions.apply();
        try {
            return run();
        } catch (Refused refused) {
            System.err.println(refused.getMessage());
            return 2;
        }
    }

    private Integer run() throws Exception {
        if (routes.isEmpty() == (scenario == null)) {
            throw new Refused("Choose one: --route <id> (repeatable) drives GET routes filled"
                    + " from their declared defaults, --scenario <file> drives a kind: bench"
                    + " document.");
        }
        // Route documents parse expressions, so module-provided functions install first.
        CliModules.installAppExtensions(app, compile.modules);
        AppManifest manifest = new ManifestLoader().load(app);
        Map<String, RouteFile> byId = new LinkedHashMap<>();
        for (RouteFile route : manifest.routes()) {
            byId.putIfAbsent(route.definition().id(), route);
        }
        BenchScenario loaded = null;
        List<BenchHarness.Target> targets = new ArrayList<>();
        if (scenario != null) {
            loaded = new SimpleYamlParser().parseBench(scenario);
            List<BenchScenarios.Problem> problems = BenchScenarios.validate(loaded,
                    manifest.routes());
            if (!problems.isEmpty()) {
                StringBuilder refusal = new StringBuilder();
                for (BenchScenarios.Problem problem : problems) {
                    refusal.append(refusal.isEmpty() ? "" : "\n").append(problem.code())
                            .append(": ").append(problem.message());
                }
                throw new Refused(refusal.toString());
            }
            for (BenchScenario.Request request : loaded.requests()) {
                targets.add(target(byId.get(request.route()), request.params(), request.body(),
                        request.weightOrOne()));
            }
        } else {
            for (String id : routes) {
                RouteFile route = byId.get(id);
                if (route == null) {
                    throw new Refused("No route '" + id + "' in " + app
                            + "; tesseraql routes --app lists the ids.");
                }
                if (BenchScenarios.isWrite(route)) {
                    throw new Refused("Route '" + id + "' is " + route.httpMethod()
                            + ", a write: drive it from a scenario that declares writes:"
                            + " allowed, so the decision to mutate data is written down.");
                }
                targets.add(target(route, Map.of(), null, 1));
            }
        }
        BenchHarness.Shape shape = shape(loaded);
        String bearer = bearer();
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        HttpClient client = BenchHarness.client();

        BenchHarness.Scrape before = noScrape ? null : scrape(client, base, bearer, true);
        BenchHarness.Result result = BenchHarness.run(client, base, targets, shape, bearer);
        BenchHarness.Scrape after = before == null ? null : scrape(client, base, bearer, false);
        if (result.requests() == 0) {
            System.err.println("No request was answered"
                    + (result.firstError() == null ? "." : ": " + result.firstError()));
            return 1;
        }
        List<BenchHarness.Check> checks;
        try {
            checks = expect == null ? List.of() : BenchHarness.evaluate(expect, result);
        } catch (IllegalArgumentException grammar) {
            throw new Refused(grammar.getMessage());
        }
        Map<String, Object> readout = before != null && after != null
                ? BenchHarness.readout(before, after)
                : null;
        if (format == Format.json) {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(json(targets, shape, result, readout, checks, loaded)));
        } else {
            System.out.print(text(targets, shape, result, readout, checks, loaded));
        }
        return checks.stream().allMatch(BenchHarness.Check::pass) ? 0 : 3;
    }

    /**
     * One target from a route: its declared inputs' defaults, the scenario's params over them,
     * path placeholders filled first and the rest sent as the query string.
     */
    private BenchHarness.Target target(RouteFile route, Map<String, Object> params, Object body,
            int weight) throws IOException {
        String id = route.definition().id();
        Map<String, Object> values = new LinkedHashMap<>();
        route.definition().input().forEach((name, field) -> {
            if (field.defaultValue() != null) {
                values.put(name, field.defaultValue());
            }
        });
        values.putAll(params);
        for (Map.Entry<String, InputField> input : route.definition().input().entrySet()) {
            if (input.getValue().required() && !values.containsKey(input.getKey())) {
                throw new Refused("Route '" + id + "' requires input '" + input.getKey()
                        + "' and declares no default; pass it in a scenario's params:.");
            }
        }
        String path = route.urlPath();
        Map<String, String> query = new LinkedHashMap<>();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            String placeholder = "{" + value.getKey() + "}";
            if (path.contains(placeholder)) {
                path = path.replace(placeholder, java.net.URLEncoder
                        .encode(String.valueOf(value.getValue()), StandardCharsets.UTF_8)
                        .replace("+", "%20"));
            } else {
                query.put(value.getKey(), String.valueOf(value.getValue()));
            }
        }
        if (path.contains("{")) {
            throw new Refused("Route '" + id + "' has a path placeholder no input fills: "
                    + path + "; pass it in a scenario's params:.");
        }
        String bodyJson = body == null ? null : MAPPER.writeValueAsString(body);
        return new BenchHarness.Target(id, route.httpMethod().toUpperCase(Locale.ROOT), path,
                query, bodyJson, BenchScenarios.isWrite(route), weight);
    }

    /** The command line over the scenario over the defaults. */
    private BenchHarness.Shape shape(BenchScenario loaded) {
        int workers = concurrency != null
                ? concurrency
                : loaded != null && loaded.concurrency() != null
                        ? loaded.concurrency()
                        : BenchScenario.DEFAULT_CONCURRENCY;
        if (workers < 1) {
            throw new Refused("--concurrency is at least 1.");
        }
        String length = duration != null
                ? duration
                : loaded != null && loaded.duration() != null
                        ? loaded.duration()
                        : BenchScenario.DEFAULT_DURATION;
        String ramp = rampUp != null ? rampUp : loaded != null ? loaded.rampUp() : null;
        Double perSecond = rate != null ? rate : loaded != null ? loaded.rate() : null;
        if (perSecond != null && perSecond <= 0) {
            throw new Refused("--rate is requests per second, above zero.");
        }
        return new BenchHarness.Shape(workers, Durations.parse(length, "--duration"),
                ramp == null ? null : Durations.parse(ramp, "--ramp-up"), perSecond);
    }

    /** {@code --token-file}, then {@code TESSERAQL_TOKEN}, then {@code --login}; none is fine. */
    private String bearer() throws Exception {
        if (tokenFile != null) {
            return Files.readString(tokenFile).trim();
        }
        String env = System.getenv("TESSERAQL_TOKEN");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        if (login == null) {
            return null;
        }
        String secret = System.getenv("TESSERAQL_PASSWORD");
        if (secret == null || secret.isBlank()) {
            java.io.Console console = System.console();
            if (console == null) {
                throw new Refused("No password for --login: set TESSERAQL_PASSWORD, or run"
                        + " where a terminal can prompt.");
            }
            char[] typed = console.readPassword("Password for %s: ", login);
            secret = typed == null ? null : new String(typed);
        }
        if (secret == null || secret.isBlank()) {
            throw new Refused("No password for --login: set TESSERAQL_PASSWORD, or run where"
                    + " a terminal can prompt.");
        }
        try {
            return TokenCommand.signInAndExchange(url, login, secret, null, null, null, null)
                    .token();
        } catch (TokenCommand.ExchangeFailed failed) {
            throw new Refused(failed.getMessage());
        }
    }

    /** One scrape, or {@code null} when the endpoint refuses or is absent (said once). */
    private static BenchHarness.Scrape scrape(HttpClient client, String base, String bearer,
            boolean first) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create(base + "/_tesseraql/metrics")).timeout(Duration.ofSeconds(10));
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return BenchHarness.Scrape.parse(response.body());
            }
            if (first) {
                System.err.println("scrape: /_tesseraql/metrics answered HTTP "
                        + response.statusCode() + " — enable tesseraql.metrics and pass a"
                        + " token holding ops.metrics.view to read the capacity signals"
                        + " beside the percentiles (--no-scrape silences this).");
            }
            return null;
        } catch (IOException | RuntimeException unreachable) {
            if (first) {
                System.err.println("scrape: /_tesseraql/metrics not read ("
                        + unreachable.getMessage() + ").");
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String text(List<BenchHarness.Target> targets, BenchHarness.Shape shape,
            BenchHarness.Result result, Map<String, Object> readout,
            List<BenchHarness.Check> checks, BenchScenario loaded) {
        StringBuilder out = new StringBuilder();
        out.append("bench ").append(names(targets)).append(" at ").append(url).append(": ");
        if (shape.openLoop()) {
            out.append(BenchHarness.tenths(shape.rate())).append(" requests/s offered, ")
                    .append(shape.concurrency()).append(" in flight at most");
        } else {
            out.append(shape.concurrency()).append(" worker(s), closed loop");
        }
        out.append(", ").append(shape.duration());
        if (shape.rampUp() != null) {
            out.append(", ramp-up ").append(shape.rampUp());
        }
        if (loaded != null && loaded.description() != null) {
            out.append(" — ").append(loaded.description());
        }
        out.append('\n');
        out.append(String.format(Locale.ROOT, "%-12s%d in %ss (%s/s)%n", "requests",
                result.requests(), BenchHarness.tenths(result.elapsed().toMillis() / 1000.0),
                BenchHarness.tenths(result.throughputPerSecond())));
        out.append(String.format(Locale.ROOT, "%-12sp50 %s   p95 %s   p99 %s   max %s%n",
                "latency ms", BenchHarness.tenths(result.percentileMillis(0.50)),
                BenchHarness.tenths(result.percentileMillis(0.95)),
                BenchHarness.tenths(result.percentileMillis(0.99)),
                BenchHarness.tenths(result.maxMillis())));
        StringBuilder statuses = new StringBuilder();
        result.statuses().forEach((status, count) -> statuses
                .append(statuses.isEmpty() ? "" : " · ").append(status).append(' ')
                .append(count));
        out.append(String.format(Locale.ROOT, "%-12s%s%n", "status", statuses));
        if (result.refusedTotal() == 0) {
            out.append(String.format(Locale.ROOT, "%-12s0%n", "refused"));
        } else {
            out.append(String.format(Locale.ROOT, "%-12s%d (%s%%)%n", "refused",
                    result.refusedTotal(), BenchHarness.tenths(result.refusedPercent())));
            result.refused().forEach((code, count) -> out.append(String.format(Locale.ROOT,
                    "%-12s  %s %d: %s%n", "", code, count, BenchHarness.gloss(code))));
        }
        out.append(String.format(Locale.ROOT, "%-12s%d%s%n", "errors", result.errors(),
                result.firstError() == null ? "" : " (first: " + result.firstError() + ")"));
        if (readout != null) {
            out.append(String.format(Locale.ROOT, "%-12s%s%n", "scrape",
                    BenchHarness.readoutLine(readout)));
        }
        if (!checks.isEmpty()) {
            StringBuilder verdicts = new StringBuilder();
            for (BenchHarness.Check check : checks) {
                verdicts.append(verdicts.isEmpty() ? "" : " · ").append(check.expression())
                        .append(check.pass() ? " ok" : " NOT MET").append(" (")
                        .append(BenchHarness.tenths(check.actual())).append(')');
            }
            out.append(String.format(Locale.ROOT, "%-12s%s%n", "expect", verdicts));
        }
        return out.toString();
    }

    private Map<String, Object> json(List<BenchHarness.Target> targets, BenchHarness.Shape shape,
            BenchHarness.Result result, Map<String, Object> readout,
            List<BenchHarness.Check> checks, BenchScenario loaded) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", app.toString());
        out.put("url", url);
        out.put("targets", targets.stream().map(BenchHarness.Target::routeId).toList());
        if (loaded != null && loaded.description() != null) {
            out.put("description", loaded.description());
        }
        out.put("mode", shape.openLoop() ? "open" : "closed");
        out.put("concurrency", shape.concurrency());
        out.put("durationMillis", shape.duration().toMillis());
        out.put("rampUpMillis", shape.rampUp() == null ? null : shape.rampUp().toMillis());
        out.put("ratePerSecond", shape.rate());
        out.put("startedAt", result.startedAt().toString());
        out.put("elapsedMillis", result.elapsed().toMillis());
        out.put("requests", result.requests());
        out.put("throughputPerSecond", round(result.throughputPerSecond()));
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50", round(result.percentileMillis(0.50)));
        latency.put("p95", round(result.percentileMillis(0.95)));
        latency.put("p99", round(result.percentileMillis(0.99)));
        latency.put("max", round(result.maxMillis()));
        out.put("latencyMillis", latency);
        Map<String, Object> statuses = new LinkedHashMap<>();
        result.statuses().forEach((status, count) -> statuses.put(String.valueOf(status), count));
        out.put("statuses", statuses);
        out.put("refused", result.refused());
        out.put("refusedPercent", round(result.refusedPercent()));
        out.put("errors", result.errors());
        out.put("firstError", result.firstError());
        out.put("scrape", readout);
        Map<String, Object> expectation = new LinkedHashMap<>();
        expectation.put("pass", checks.stream().allMatch(BenchHarness.Check::pass));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BenchHarness.Check check : checks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("expression", check.expression());
            row.put("actual", round(check.actual()));
            row.put("pass", check.pass());
            rows.add(row);
        }
        expectation.put("checks", rows);
        out.put("expect", expect == null ? null : expectation);
        return out;
    }

    private static String names(List<BenchHarness.Target> targets) {
        List<String> ids = new ArrayList<>();
        for (BenchHarness.Target target : targets) {
            if (!ids.contains(target.routeId())) {
                ids.add(target.routeId());
            }
        }
        return String.join(", ", ids);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
