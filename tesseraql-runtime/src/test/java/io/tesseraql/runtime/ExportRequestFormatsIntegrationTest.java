package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A request-sourced export value is refused before the SQL runs, and the fallback chain is one
 * rule on every arm (docs/export-declarations.md decisions 27-36).
 *
 * <p>The fixture makes the four rungs of the chain distinguishable on one row: {@code held_at}
 * is the instant {@code 2026-01-15 22:30:00+00}, the configuration says {@code Asia/Kolkata} and
 * {@code de} (a half-hour offset no host runs in, and a locale whose decimal separator differs
 * from the platform's), and the JVM is pinned to UTC and {@code en-US} for the class. So a
 * request-sourced {@code Asia/Tokyo} renders {@code 07:30}, the configured zone {@code 04:00}
 * the next day, the platform {@code 22:30}, and a host flipped to Los Angeles {@code 14:30};
 * {@code 1234.5} is {@code "1.234,50"} under the configuration and {@code "1,234.50"} under the
 * platform.
 *
 * <p>Every "no SQL ran" assertion reads the pair {@code (last_value, is_called)} of a sequence
 * the extraction advances — PostgreSQL's first {@code nextval} on a fresh sequence leaves
 * {@code last_value} at 1, so the fixture primes it and the refusal test runs first, on a fresh
 * database, on purpose.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExportRequestFormatsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ZONE_TEXT = "Not a time zone this app can use (expected e.g."
            + " Asia/Tokyo or UTC).";
    private static final String CLAIM_ZONE_TEXT = "The time zone in your sign-in profile is not"
            + " one this app can use; ask whoever manages your account to correct it.";
    private static final String SOURCE_ZONE_CELL = "2026-01-16 07:30:00";
    private static final String CONFIG_ZONE_CELL = "2026-01-16 04:00:00";
    private static final String CONFIG_LOCALE_CELL = "\"1.234,50\"";
    private static final String PLATFORM_LOCALE_CELL = "\"1,234.50\"";
    private static final String FULLWIDTH_LOCALE_CELL = "１,２３４.５０";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path inbound;
    static int port;
    static TimeZone previousZone;
    static Locale previousLocale;

    @BeforeAll
    static void start() throws Exception {
        // The configured zone must differ from the host's, or the CONFIG rung is
        // indistinguishable from the platform default on this machine.
        assertThat(ZoneId.systemDefault().getId()).isNotEqualTo("Asia/Kolkata");
        previousZone = TimeZone.getDefault();
        previousLocale = Locale.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));
        Locale.setDefault(Locale.US);
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        port = runtime.port();
    }

    @AfterAll
    static void stop() throws IOException {
        try {
            if (runtime != null) {
                runtime.close();
            }
            if (appHome != null) {
                deleteRecursively(appHome);
            }
        } finally {
            TimeZone.setDefault(previousZone);
            Locale.setDefault(previousLocale);
        }
    }

    /**
     * Runs first, on a fresh database: a refusal parked until the reader lambda would advance
     * the sequence on the very first request, which is the one a {@code last_value}-only check
     * cannot see. Every shape the codec would refuse is refused here — a bare name, a short id,
     * padding on either side, a case fold, a typo — as the caller's 400 in the input binder's
     * envelope, with no value on the wire and nothing extracted.
     */
    @Test
    @Order(1)
    void aBadRequestSourcedZoneIsRefusedBeforeTheSqlRuns() throws Exception {
        for (String bad : List.of("Tokyo", "PST", "%20Asia/Tokyo", "Asia/Tokyo%20", "asia/tokyo",
                "Asia/Tokio")) {
            String before = sequence();
            HttpResponse<String> response = get("/api/x/seq?tz=" + bad, null);
            String after = sequence();

            assertThat(response.statusCode()).as(bad).isEqualTo(400);
            JsonNode error = json(response).get("error");
            assertThat(error.get("code").asText()).as(bad).isEqualTo("TQL-FIELD-2001");
            JsonNode field = error.at("/details/fields/0");
            assertThat(field.get("field").asText()).as(bad).isEqualTo("tz");
            assertThat(field.get("code").asText()).as(bad).isEqualTo("timezone");
            assertThat(field.get("source").asText()).as(bad).isEqualTo("query.tz");
            assertThat(field.has("value")).as("no value on the wire for " + bad).isFalse();
            assertThat(field.get("message").asText()).as(bad).isEqualTo(ZONE_TEXT);
            assertThat(after).as("the extraction ran for " + bad).isEqualTo(before);
        }
    }

    /** The positive control: a valid source value reaches the cell and the SQL runs. */
    @Test
    void aRequestSourcedZoneResolvesAndTheSqlRuns() throws Exception {
        String before = sequence();
        HttpResponse<String> response = get("/api/x/seq?tz=Asia/Tokyo", null);
        String after = sequence();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(SOURCE_ZONE_CELL);
        assertThat(after).as("the extraction ran").isNotEqualTo(before);
    }

    /**
     * The locale twin: {@code ja_JP} from a query source keeps the strict rule; an extension
     * passes and is applied; an absent source falls to the configured {@code de}.
     */
    @Test
    void aBadRequestSourcedLocaleIsRefusedBeforeTheSqlRuns() throws Exception {
        String before = sequence();
        HttpResponse<String> refused = get("/api/x/loc?loc=ja_JP", null);
        String after = sequence();

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).at("/error/details/fields/0/code").asText()).isEqualTo("locale");
        assertThat(after).as("the extraction ran").isEqualTo(before);

        HttpResponse<String> fullwidth = get("/api/x/loc?loc=ja-JP-u-nu-fullwide", null);
        assertThat(fullwidth.statusCode()).isEqualTo(200);
        assertThat(fullwidth.body()).contains(FULLWIDTH_LOCALE_CELL);

        HttpResponse<String> configured = get("/api/x/loc", null);
        assertThat(configured.statusCode()).isEqualTo(200);
        assertThat(configured.body()).contains(CONFIG_LOCALE_CELL);
    }

    /** An absent or blank source is the configured zone, not the JVM's (which would be 22:30). */
    @Test
    void anUnresolvedSourceFallsToTheConfiguredZoneNotTheJvm() throws Exception {
        HttpResponse<String> absent = get("/api/x/seq", null);
        HttpResponse<String> blank = get("/api/x/seq?tz=", null);

        assertThat(absent.statusCode()).isEqualTo(200);
        assertThat(absent.body()).contains(CONFIG_ZONE_CELL);
        assertThat(blank.statusCode()).isEqualTo(200);
        assertThat(blank.body()).contains(CONFIG_ZONE_CELL);
    }

    /** Red when the cell follows the host: a Los Angeles host would render 14:30. */
    @Test
    void theConfiguredZoneHoldsOnAHostInAnotherZone() throws Exception {
        TimeZone pinned = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertThat(ZoneId.systemDefault().getId()).as("the control: the host moved")
                    .isEqualTo("America/Los_Angeles");

            HttpResponse<String> response = get("/api/x/seq", null);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(CONFIG_ZONE_CELL);
        } finally {
            TimeZone.setDefault(pinned);
        }
    }

    /**
     * A sign-in claim the app cannot use is refused naming the claim — the whole expression as
     * the field, under the claim's own text, which says whose value it is — and nothing ran; a
     * usable claim reaches the cell, and an absent one falls to the configuration.
     */
    @Test
    void aBadIdpClaimIsRefusedNamingTheClaim() throws Exception {
        for (Object bad : List.of("Asia/Tokio", 9, List.of("Asia/Tokyo"))) {
            String before = sequence();
            HttpResponse<String> response = get("/api/x/claim",
                    token(Map.of("sub", "anne", "zoneinfo", bad)));
            String after = sequence();

            assertThat(response.statusCode()).as(String.valueOf(bad)).isEqualTo(400);
            JsonNode field = json(response).at("/error/details/fields/0");
            assertThat(field.get("field").asText()).as(String.valueOf(bad))
                    .isEqualTo("principal.claim.zoneinfo");
            assertThat(field.get("messageKey").asText()).isEqualTo("tql.input.claim.timezone");
            assertThat(field.get("message").asText()).isEqualTo(CLAIM_ZONE_TEXT);
            assertThat(after).as("the extraction ran for " + bad).isEqualTo(before);
        }

        HttpResponse<String> good = get("/api/x/claim",
                token(Map.of("sub", "anne", "zoneinfo", "Asia/Tokyo")));
        assertThat(good.statusCode()).isEqualTo(200);
        assertThat(good.body()).contains(SOURCE_ZONE_CELL);

        HttpResponse<String> absent = get("/api/x/claim", token(Map.of("sub", "anne")));
        assertThat(absent.statusCode()).isEqualTo(200);
        assertThat(absent.body()).contains(CONFIG_ZONE_CELL);
    }

    /**
     * OpenID Connect allows a locale claim spelled {@code en_US} (decision 30): it is read as
     * {@code en-US} and applied; {@code de_DE} likewise; a value no fold can save is refused
     * under the claim's own key.
     */
    @Test
    void anUnderscoreLocaleClaimIsAcceptedInItsDashForm() throws Exception {
        HttpResponse<String> enUs = get("/api/x/claim",
                token(Map.of("sub", "anne", "zoneinfo", "Asia/Tokyo", "locale", "en_US")));
        HttpResponse<String> deDe = get("/api/x/claim",
                token(Map.of("sub", "anne", "zoneinfo", "Asia/Tokyo", "locale", "de_DE")));
        HttpResponse<String> japanese = get("/api/x/claim",
                token(Map.of("sub", "anne", "zoneinfo", "Asia/Tokyo", "locale", "japanese")));

        assertThat(enUs.statusCode()).isEqualTo(200);
        assertThat(enUs.body()).contains(PLATFORM_LOCALE_CELL);
        assertThat(deDe.statusCode()).isEqualTo(200);
        assertThat(deDe.body()).contains(CONFIG_LOCALE_CELL);
        assertThat(japanese.statusCode()).isEqualTo(400);
        JsonNode field = json(japanese).at("/error/details/fields/0");
        assertThat(field.get("field").asText()).isEqualTo("principal.claim.locale");
        assertThat(field.get("messageKey").asText()).isEqualTo("tql.input.claim.locale");
    }

    /**
     * The asynchronous arm: a refusal answers 400 before {@code startExport}, so no transfer
     * row and no execution row exist afterwards — where a 202-then-FAILED used to hide the
     * reason. The documents of the controls render the chain: the request-sourced zone and
     * locale when sent, the configured zone and locale when not.
     */
    @Test
    void aFileExportRefusalLeavesNoRowAndTheDocumentsRenderTheChain() throws Exception {
        HttpResponse<String> zone = post("/api/x/feseq", "{\"tz\":\"Tokyo\"}",
                "application/json", null);
        HttpResponse<String> locale = post("/api/x/feseq", "{\"loc\":\"ja_JP\"}",
                "application/json", null);
        HttpResponse<String> number = post("/api/x/feseq", "{\"tz\":9}",
                "application/json", null);
        Thread.sleep(300);

        assertThat(zone.statusCode()).isEqualTo(400);
        assertThat(json(zone).at("/error/details/fields/0/field").asText()).isEqualTo("tz");
        assertThat(locale.statusCode()).isEqualTo(400);
        assertThat(json(locale).at("/error/details/fields/0/field").asText()).isEqualTo("loc");
        assertThat(json(locale).at("/error/details/fields/0/code").asText()).isEqualTo("locale");
        assertThat(number.statusCode()).isEqualTo(400);
        assertThat(json(number).at("/error/details/fields/0/field").asText()).isEqualTo("tz");
        assertThat(count("select count(*) from tql_file_transfer where route_id = 'x.feseq'"))
                .as("transfer rows after three refusals").isZero();
        assertThat(count("select count(*) from tql_job_execution where job_id = 'x.feseq'"))
                .as("execution rows after three refusals").isZero();

        String sourced = fileExport("{\"tz\":\"Asia/Tokyo\"}");
        assertThat(sourced).contains(SOURCE_ZONE_CELL);

        String configured = fileExport("{}");
        assertThat(configured).contains(CONFIG_ZONE_CELL).contains(CONFIG_LOCALE_CELL);

        // The request-sourced locale must differ from the platform's AND the configuration's,
        // or a dropped locale would be invisible: fullwidth digits are neither.
        String both = fileExport("{\"tz\":\"Asia/Tokyo\",\"loc\":\"ja-JP-u-nu-fullwide\"}");
        assertThat(both).contains(FULLWIDTH_LOCALE_CELL).contains(SOURCE_ZONE_CELL);

        assertThat(count("select count(*) from tql_file_transfer where route_id = 'x.feseq'"))
                .as("transfer rows after three controls").isEqualTo(3);
    }

    /**
     * The import arm judges the claim before a row is parsed: {@code japanese} is a 400 with no
     * transfer and no row (it used to parse {@code 99,90} as {@code 9990.00} in the root
     * locale); {@code de-DE}, its underscore twin and {@code en_US} each parse in their own
     * locale.
     */
    @Test
    void aFileImportClaimLocaleIsRefusedBeforeParsing() throws Exception {
        long before = count("select count(*) from imported");
        HttpResponse<String> refused = post("/api/x/impc", "name,amount\ndelta,\"99,90\"\n",
                "text/csv", token(Map.of("sub", "anne", "locale", "japanese")));
        Thread.sleep(300);

        assertThat(refused.statusCode()).isEqualTo(400);
        JsonNode field = json(refused).at("/error/details/fields/0");
        assertThat(field.get("field").asText()).isEqualTo("principal.claim.locale");
        assertThat(field.get("messageKey").asText()).isEqualTo("tql.input.claim.locale");
        assertThat(count("select count(*) from imported")).isEqualTo(before);
        assertThat(count("select count(*) from tql_file_transfer where route_id = 'x.impc'"))
                .isZero();

        fileImport("/api/x/impc", "name,amount\ngamma,\"1.234,50\"\n",
                token(Map.of("sub", "anne", "locale", "de-DE")));
        fileImport("/api/x/impc", "name,amount\nkappa,\"2.345,60\"\n",
                token(Map.of("sub", "anne", "locale", "de_DE")));
        fileImport("/api/x/impc", "name,amount\nlambda,\"3,456.70\"\n",
                token(Map.of("sub", "anne", "locale", "en_US")));

        assertThat(scalar("select string_agg(name || '=' || amount, ' ; ' order by name)"
                + " from imported where name in ('gamma', 'kappa', 'lambda')"))
                .isEqualTo("gamma=1234.50 ; kappa=2345.60 ; lambda=3456.70");
    }

    /** The import arm's CONFIG rung: no {@code locale:} at all parses in the configured {@code de}. */
    @Test
    void aFileImportWithNoLocaleDeclaredParsesInTheConfiguredLocale() throws Exception {
        fileImport("/api/x/impcfg", "name,amount\nepsilon,\"1.234,50\"\n", null);

        assertThat(scalar("select amount from imported where name = 'epsilon'"))
                .isEqualTo("1234.50");
    }

    /** {@code request.locale} is the framework's negotiated answer: served, never judged. */
    @Test
    void theNegotiatedRequestLocaleIsServedNotJudged() throws Exception {
        HttpResponse<String> german = get("/api/x/reqloc", null, "Accept-Language", "de");
        HttpResponse<String> english = get("/api/x/reqloc", null, "Accept-Language", "en");

        assertThat(german.statusCode()).isEqualTo(200);
        assertThat(german.body()).contains(CONFIG_LOCALE_CELL);
        assertThat(english.statusCode()).isEqualTo(200);
        assertThat(english.body()).contains(PLATFORM_LOCALE_CELL);
    }

    /**
     * A refusal is the caller's, so it is logged at DEBUG only — the value never reaches a line
     * at INFO or above, whatever it carries. The presence control is a genuine server fault on
     * the same runtime: without it, an empty capture would also mean the capture saw nothing.
     */
    @Test
    void aRefusedValueNeverReachesTheLogAtInfoOrAbove() throws Exception {
        // Two log roads reach stderr here: SLF4J (the runner's lines, slf4j-simple) and
        // java.util.logging (the System.Logger the binders use, whose console handler bound
        // stderr long before this test). Watch both: the captured stream and a handler on the
        // JUL root logger.
        List<java.util.logging.LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.INFO.intValue()) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.addHandler(handler);
        String refusals;
        String control;
        try {
            refusals = captureStderr(() -> {
                assertThat(get("/api/x/seq?tz=Asia/Tokyo%0AFORGED", null).statusCode())
                        .isEqualTo(400);
                assertThat(get("/api/x/seq?tz=" + "A".repeat(300), null).statusCode())
                        .isEqualTo(400);
                assertThat(get("/api/x/seq?tz=Tokyo", null).statusCode()).isEqualTo(400);
            });
            List<String> julDuringRefusals = records.stream()
                    .map(record -> record.getLevel() + " " + record.getMessage() + " "
                            + java.util.Arrays.toString(record.getParameters()))
                    .toList();
            records.clear();
            control = captureStderr(() -> {
                assertThat(get("/api/x/codec", null).statusCode()).isEqualTo(500);
                // The JUL road's presence control: a WARNING through the same System.Logger
                // facade the binders use lands in the handler (or, if the facade were routed
                // elsewhere, in the captured stream).
                System.getLogger("io.tesseraql.compiler.binding.probe")
                        .log(System.Logger.Level.WARNING, "PROBE-WARNING x.seq query.tz");
            });
            assertThat(julDuringRefusals.stream()
                    .filter(line -> line.contains("x.seq") || line.contains("query.tz")
                            || line.contains("timezone") || line.contains("Refusing")))
                    .as("a JUL record at INFO or above about the refusal").isEmpty();
            boolean probeSeen = records.stream()
                    .anyMatch(record -> String.valueOf(record.getMessage())
                            .contains("PROBE-WARNING"))
                    || control.contains("PROBE-WARNING");
            assertThat(probeSeen).as("the JUL presence control: the probe WARNING was observed")
                    .isTrue();
        } finally {
            root.removeHandler(handler);
        }

        assertThat(refusals).doesNotContain("FORGED").doesNotContain("AAAAAAAA");
        assertThat(refusals.lines()
                .filter(line -> line.contains(" ERROR ") || line.contains(" WARN ")
                        || line.contains(" INFO ") || line.contains("WARNING:")
                        || line.contains("SEVERE:") || line.contains("INFO:"))
                .filter(line -> line.contains("x.seq") || line.contains("query.tz")
                        || line.contains("timezone") || line.contains("Refusing")))
                .as("a line at INFO or above about the refusal").isEmpty();
        assertThat(control.lines()
                .filter(line -> line.contains(" ERROR ")
                        && line.contains("Route 'x.codec' failed with TQL-LD-2802")))
                .as("the presence control: one ERROR line for the codec failure").hasSize(1);
    }

    /**
     * A column format the codec refuses at the cell fails AFTER the query ran: its own code,
     * naming the format and the document, not the SQL file that ran to completion.
     */
    @Test
    void aFailureWhileWritingTheDocumentIsFiledUnderItsOwnCode() throws Exception {
        AtomicReference<HttpResponse<String>> response = new AtomicReference<>();
        String log = captureStderr(() -> response.set(get("/api/x/codec", null)));

        assertThat(response.get().statusCode()).isEqualTo(500);
        assertThat(json(response.get()).at("/error/code").asText()).isEqualTo("TQL-LD-2802");
        List<String> lines = log.lines().toList();
        int at = lines.indexOf(lines.stream()
                .filter(line -> line.contains("Route 'x.codec' failed with TQL-LD-2802"))
                .findFirst().orElse(null));
        assertThat(at).as("the runner's ERROR line").isNotNegative();
        // The line, the exception it carries and the first frame: the message names the format
        // and the document, never the SQL file or the route's path.
        String head = String.join("\n", lines.subList(at, Math.min(at + 3, lines.size())));
        assertThat(head).contains("Writing the csv document failed after the query ran")
                .contains("x.codec.csv")
                .doesNotContain("export.sql").doesNotContain("SQL execution failed")
                .doesNotContain("/web/api/");
    }

    /** A statement that fails keeps the SQL code and names the SQL file. */
    @Test
    void aStatementFailureStaysASqlExecutionFailure() throws Exception {
        AtomicReference<HttpResponse<String>> response = new AtomicReference<>();
        String log = captureStderr(() -> response.set(get("/api/x/badsql", null)));

        assertThat(response.get().statusCode()).isEqualTo(500);
        assertThat(json(response.get()).at("/error/code").asText()).isEqualTo("TQL-SQL-2500");
        assertThat(log).contains("SQL execution failed").contains("export.sql");
    }

    /**
     * The job arm through the served runtime's executor: a step that declares nothing renders in
     * the configured zone and locale; a sibling's zone literal wins over the configuration and
     * its unset locale still falls to it.
     */
    @Test
    void anExportStepWithNoDeclarationRendersInTheConfiguredZoneAndLocale() throws Exception {
        JobExecution execution = runtime.runJob("nightly", Map.of());

        assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
        String report = download(latestTransfer("nightly#report"));
        assertThat(report).contains(CONFIG_ZONE_CELL).contains(CONFIG_LOCALE_CELL);
        String tokyo = download(latestTransfer("nightly#tokyo"));
        assertThat(tokyo).contains(SOURCE_ZONE_CELL).contains(CONFIG_LOCALE_CELL);
    }

    /**
     * The poll-triggered import: no {@code import.locale:} means the configured {@code de}, so
     * {@code 2.345,60} parses and the file lands in {@code .done}; it used to parse in the
     * JVM's locale, fail, and land in {@code .error}.
     */
    @Test
    void aPollImportWithoutALocaleReadsTheConfiguredLocale() throws Exception {
        Files.writeString(inbound.resolve("prices.csv"), "name,amount\nomega,\"2.345,60\"\n");

        String moved = null;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (moved == null && Instant.now().isBefore(deadline)) {
            Thread.sleep(250);
            try (Stream<Path> files = Files.walk(inbound)) {
                moved = files.filter(Files::isRegularFile)
                        .filter(file -> !file.getParent().equals(inbound))
                        .map(file -> inbound.relativize(file).toString())
                        .findFirst().orElse(null);
            }
        }

        assertThat(moved).as("the poll consumed the file").isNotNull().startsWith(".done");
        assertThat(scalar("select amount from imported where name = 'omega'"))
                .isEqualTo("2345.60");
    }

    // --- helpers ---

    private static HttpResponse<String> get(String path, String bearer, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body, String contentType,
            String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    /** Starts the file-export with {@code body}, waits for it and returns its document. */
    private static String fileExport(String body) throws Exception {
        HttpResponse<String> accepted = post("/api/x/feseq", body, "application/json", null);
        assertThat(accepted.statusCode()).as(body).isEqualTo(202);
        JsonNode started = json(accepted);
        assertThat(awaitTerminal(started.get("statusUrl").asText(), null)).as(body)
                .isEqualTo("COMPLETED");
        return download(started.get("transferId").asText());
    }

    /** Uploads {@code csv} to the import route and waits for the run to complete. */
    private static void fileImport(String path, String csv, String bearer) throws Exception {
        HttpResponse<String> accepted = post(path, csv, "text/csv", bearer);
        assertThat(accepted.statusCode()).as(csv).isEqualTo(202);
        assertThat(awaitTerminal(json(accepted).get("statusUrl").asText(), bearer)).as(csv)
                .isEqualTo("COMPLETED");
    }

    private static String awaitTerminal(String statusUrl, String bearer) throws Exception {
        String path = statusUrl.startsWith("http") ? URI.create(statusUrl).getPath() : statusUrl;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            String status = json(get(path, bearer)).get("status").asText();
            if (!"RUNNING".equals(status) && !"STARTED".equals(status)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static String download(String transferId) throws Exception {
        var download = runtime.fileTransfers().download(transferId);
        assertThat(download).as("a document for " + transferId).isPresent();
        try (InputStream content = download.get().content()) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String latestTransfer(String routeId) throws Exception {
        return scalar("select transfer_id from tql_file_transfer where route_id = '" + routeId
                + "' order by created_at desc limit 1");
    }

    /** The pair a {@code last_value}-only read is blind to on a fresh sequence. */
    private static String sequence() throws Exception {
        return scalar("select last_value::text || '|' || is_called::text from probe_seq");
    }

    private static long count(String sql) throws Exception {
        return Long.parseLong(scalar(sql));
    }

    private static String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    /** slf4j-simple resolves {@code System.err} per line, so swapping the stream captures it. */
    private static String captureStderr(ThrowingRunnable body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
            Thread.sleep(300);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String token(Map<String, ?> claims) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                TestClaims.addressed(new LinkedHashMap<>(claims))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    // --- the fixture ---

    private static final String SQL = "select name, held_at, fee, nextval('probe_seq') as n"
            + " from events order by name\n";
    private static final String COLUMNS = """
              columns:
                - { name: name }
                - { name: held_at, type: datetime }
                - { name: fee, type: number, format: "#,##0.00" }
                - { name: n }
            """;

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("export-request-formats-it");
        inbound = Files.createDirectories(home.resolve("inbound"));
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: probe
                  i18n:
                    defaultLocale: en
                    locales: [en, ja, de]
                  files:
                    timezone: Asia/Kolkata
                    locale: de
                  connectors:
                    poll:
                      allowedPaths:
                        - %s
                  security:
                    jwt:
                      secret: %s
                      audience: %s
                      rolesClaim: roles
                      permissionsClaim: permissions
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(inbound.toAbsolutePath(), JWT_SECRET, TestClaims.INLINE_FIXTURE,
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Files.createDirectories(home.resolve("db/migration"));
        // The sequence is PRIMED (is_called = true) so the very first nextval moves last_value.
        Files.writeString(home.resolve("db/migration/V1__tables.sql"), """
                create table events (name varchar(100) primary key,
                                     held_at timestamptz not null,
                                     fee numeric(12, 2) not null);
                insert into events (name, held_at, fee)
                values ('alpha', timestamptz '2026-01-15 22:30:00+00', 1234.5);
                create sequence probe_seq start 1;
                select setval('probe_seq', 1, true);
                create table imported (name varchar(100) primary key,
                                       amount numeric(12, 2) not null);
                """);

        writeQueryExport(home, "seq", "x.seq", "  timezone: query.tz\n",
                "input:\n  tz: { type: string }\n", "", COLUMNS, SQL);
        writeQueryExport(home, "plain", "x.plain", "", "", "", COLUMNS, SQL);
        writeQueryExport(home, "literal", "x.literal", "  timezone: Asia/Kolkata\n", "", "",
                COLUMNS, SQL);
        writeQueryExport(home, "loc", "x.loc", "  locale: query.loc\n",
                "input:\n  loc: { type: string }\n", "", """
                          columns:
                            - { name: name }
                            - { name: held_at, type: datetime, format: 'yyyy MMM dd' }
                            - { name: fee, type: number, format: "#,##0.00" }
                            - { name: n }
                        """, SQL);
        writeQueryExport(home, "claim", "x.claim",
                "  timezone: principal.claim.zoneinfo\n  locale: principal.claim.locale\n", "",
                "security:\n  auth: bearer\n", COLUMNS, SQL);
        writeQueryExport(home, "reqloc", "x.reqloc", "  locale: request.locale\n", "", "",
                COLUMNS, SQL);
        // An untyped column with a pattern DateTimeFormatter accepts and DecimalFormat refuses:
        // the declaration passes boot, the value is a number at run time, and the cell throws.
        writeQueryExport(home, "codec", "x.codec", "", "", "", """
                  columns:
                    - { name: name }
                    - { name: fee, format: 'dd.MM.yyyy' }
                    - { name: n }
                """, SQL);
        writeQueryExport(home, "badsql", "x.badsql", "", "", "", COLUMNS,
                "select name, held_at, fee, no_such_column from events\n");

        writeFileImport(home, "impcfg", "x.impcfg", "");
        writeFileImport(home, "impc", "x.impc", "security:\n  auth: bearer\n");
        Path feseq = Files.createDirectories(home.resolve("web/api/x/feseq"));
        Files.writeString(feseq.resolve("export.sql"), SQL);
        Files.writeString(feseq.resolve("post.yml"), """
                version: tesseraql/v1
                id: x.feseq
                kind: route
                recipe: file-export
                input:
                  tz: { type: string }
                  loc: { type: string }
                export:
                  format: csv
                  filename: x.feseq.csv
                  timezone: body.tz
                  locale: body.loc
                %ssources:
                  main:
                    sql:
                      file: export.sql
                """.formatted(COLUMNS));

        Path intake = Files.createDirectories(home.resolve("batch/intake"));
        Files.writeString(intake.resolve("row.sql"),
                "insert into imported (name, amount) values (/*name*/'x', /*amount*/0)\n");
        Files.writeString(intake.resolve("job.yml"), """
                version: tesseraql/v1
                id: intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: %s
                    include: "*.csv"
                    delay: 300ms
                import:
                  format: csv
                  onError: rollback
                  columns:
                    - { name: name }
                    - { name: amount, type: number, format: "#,##0.00" }
                pipeline:
                  - id: row
                    sql:
                      file: row.sql
                """.formatted(inbound.toAbsolutePath()));

        Path nightly = Files.createDirectories(home.resolve("batch/nightly"));
        Files.writeString(nightly.resolve("report.sql"),
                "select name, held_at, fee from events order by name\n");
        Files.writeString(nightly.resolve("job.yml"), """
                version: tesseraql/v1
                id: nightly
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: report.sql
                      mode: query
                    export:
                      format: csv
                      filename: nightly.csv
                      columns:
                        - { name: name }
                        - { name: held_at, type: datetime }
                        - { name: fee, type: number, format: "#,##0.00" }
                  - id: tokyo
                    sql:
                      file: report.sql
                      mode: query
                    export:
                      format: csv
                      filename: nightly-tokyo.csv
                      timezone: Asia/Tokyo
                      columns:
                        - { name: name }
                        - { name: held_at, type: datetime }
                        - { name: fee, type: number, format: "#,##0.00" }
                """);
        return home;
    }

    private static void writeQueryExport(Path home, String dir, String id, String declarations,
            String input, String security, String columns, String sql) throws Exception {
        Path route = Files.createDirectories(home.resolve("web/api/x/" + dir));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-export
                %s%ssources:
                  main:
                    sql:
                      file: export.sql
                export:
                  format: csv
                  filename: %s.csv
                %s%s""".formatted(id, security, input, id, declarations, columns));
        Files.writeString(route.resolve("export.sql"), sql);
    }

    private static void writeFileImport(Path home, String dir, String id, String security)
            throws Exception {
        Path route = Files.createDirectories(home.resolve("web/api/x/" + dir));
        Files.writeString(route.resolve("row.sql"),
                "insert into imported (name, amount) values (/*name*/'x', /*amount*/0)\n");
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: file-import
                %simport:
                  format: csv
                %s  columns:
                    - { name: name }
                    - { name: amount, type: number, format: "#,##0.00" }
                steps:
                  - id: row
                    sql:
                      file: row.sql
                """.formatted(id, security,
                "x.impc".equals(id) ? "  locale: principal.claim.locale\n" : ""));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
    }
}
