package io.tesseraql.test;

import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.validation.ValidationRules;
import io.tesseraql.coverage.SqlCoverableLines;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.ValidationRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Runs a declarative {@link TestSuite} against a database, executing SQL files, Identity SQL
 * Contracts, and route validation rules and checking the result rows (design ch. 13, 13.2;
 * roadmap Phase 19 — a validation case's violations are its rows).
 *
 * <p>A {@code sql} case runs inside a manual-commit transaction that is always rolled back, so a
 * write file (an {@code UPDATE}/{@code INSERT}/{@code DELETE}) is a first-class target: its
 * affected-row count is asserted with {@code expect.updateCount}, and {@code verify:} read-back
 * steps observe the uncommitted write on the same connection before the rollback. A test run
 * never commits anything to the database.
 */
public final class TestRunner {

    private final DataSource dataSource;
    private final Path appHome;
    private final IdentityService identity;
    private final RealmConfig realm;
    private final SqlCoverage coverage;
    private AppManifest manifest;

    public TestRunner(DataSource dataSource, Path appHome) {
        this(dataSource, appHome, null, null, null);
    }

    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity,
            RealmConfig realm) {
        this(dataSource, appHome, identity, realm, null);
    }

    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity,
            RealmConfig realm,
            SqlCoverage coverage) {
        this.dataSource = dataSource;
        this.appHome = appHome;
        this.identity = identity;
        this.realm = realm;
        this.coverage = coverage;
    }

    /** Runs all cases and returns a report. */
    public TestReport run(TestSuite suite) {
        List<TestResult> results = new ArrayList<>();
        for (TestCase test : suite.tests()) {
            results.add(runCase(test));
        }
        return new TestReport(results);
    }

    private TestResult runCase(TestCase test) {
        try {
            if (test.sql() != null && test.sql().file() != null) {
                return runSqlCase(test);
            }
            if (!test.verify().isEmpty()) {
                throw new IllegalArgumentException("Test '" + test.name()
                        + "' declares verify: steps, which require a sql target");
            }
            List<Map<String, Object>> rows = resultRows(test);
            return assertExpectation(test, rows);
        } catch (RuntimeException ex) {
            return TestResult.fail(test.name(), ex.getMessage());
        }
    }

    private List<Map<String, Object>> resultRows(TestCase test) {
        if (test.validate() != null) {
            return evaluateValidation(test);
        }
        if (test.notifications() != null) {
            return evaluateNotify(test);
        }
        if (test.httpCall() != null) {
            return evaluateHttpCall(test);
        }
        if (test.messages() != null) {
            return evaluateMessages(test);
        }
        if (test.contract() != null && !test.contract().isBlank()) {
            if (identity == null || realm == null) {
                throw new IllegalStateException(
                        "Contract tests require an identity service and realm");
            }
            return identity.execute(realm, stripIdentityPrefix(test.contract()), test.params());
        }
        throw new IllegalArgumentException(
                "Test '" + test.name() + "' has no sql, contract, or validate target");
    }

    /**
     * Runs a {@code sql} case — the target file plus its {@code verify:} read-backs — on one
     * connection, inside a manual-commit transaction that is always rolled back. A write file
     * (an {@code UPDATE}/{@code INSERT}/{@code DELETE}) therefore executes for real and is
     * asserted through {@code expect.updateCount} and the verify steps, yet a test run never
     * commits anything to the database — pass or fail.
     */
    private TestResult runSqlCase(TestCase test) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SqlOutcome outcome = executeSql(connection,
                        appHome.resolve(test.sql().file()), test.params());
                String failure = assertOutcome(test.expect(), outcome);
                for (int i = 0; failure == null && i < test.verify().size(); i++) {
                    failure = runVerifyStep(connection, test.verify().get(i), i);
                }
                return failure == null
                        ? TestResult.pass(test.name())
                        : TestResult.fail(test.name(), failure);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("SQL execution failed: " + ex.getMessage(), ex);
        }
    }

    /** Runs one read-back on the case's transaction; null on pass, else the failure message. */
    private String runVerifyStep(Connection connection, TestSuite.VerifyStep step, int index) {
        String label = "verify[" + index + "]";
        if (step.sql() == null || step.sql().file() == null) {
            throw new IllegalArgumentException(label + " needs a sql.file target");
        }
        SqlOutcome outcome = executeSql(connection, appHome.resolve(step.sql().file()),
                step.params());
        if (outcome.rows() == null) {
            throw new IllegalArgumentException(label + " (" + step.sql().file()
                    + ") is a write; verify steps are read-backs and must return rows");
        }
        String failure = assertOutcome(step.expect(), outcome);
        return failure == null ? null : label + " (" + step.sql().file() + "): " + failure;
    }

    /** Plain comma-joined address list, so a row column compares as a simple string. */
    private static String addresses(jakarta.mail.Address[] list) {
        if (list == null) {
            return "";
        }
        return java.util.Arrays.stream(list).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private TestResult assertExpectation(TestCase test, List<Map<String, Object>> rows) {
        String failure = assertOutcome(test.expect(), new SqlOutcome(rows, null));
        return failure == null
                ? TestResult.pass(test.name())
                : TestResult.fail(test.name(), failure);
    }

    /** Checks an expectation against an outcome; null on pass, else the failure message. */
    private static String assertOutcome(Expectation expect, SqlOutcome outcome) {
        if (expect == null) {
            return null;
        }
        if (outcome.updateCount() != null) {
            if (expect.rowCount() != null || !expect.rows().isEmpty()) {
                return "the target is a write affecting " + outcome.updateCount()
                        + " row(s); assert expect.updateCount, not rowCount/rows";
            }
            if (expect.updateCount() != null
                    && outcome.updateCount().intValue() != expect.updateCount()) {
                return "expected updateCount " + expect.updateCount() + " but was "
                        + outcome.updateCount();
            }
            return null;
        }
        List<Map<String, Object>> rows = outcome.rows();
        if (expect.updateCount() != null) {
            return "expected updateCount " + expect.updateCount()
                    + " but the target returned " + rows.size()
                    + " result row(s); assert rowCount/rows";
        }
        if (expect.rowCount() != null && rows.size() != expect.rowCount()) {
            return "expected rowCount " + expect.rowCount() + " but was " + rows.size();
        }
        for (int i = 0; i < expect.rows().size(); i++) {
            if (i >= rows.size()) {
                return "expected at least " + (i + 1) + " rows";
            }
            Map<String, Object> actual = rows.get(i);
            for (Map.Entry<String, Object> entry : expect.rows().get(i).entrySet()) {
                if (!looselyEqual(actual.get(entry.getKey()), entry.getValue())) {
                    return "row " + i + " field '" + entry.getKey()
                            + "' expected " + entry.getValue() + " but was "
                            + actual.get(entry.getKey());
                }
            }
        }
        return null;
    }

    /**
     * Evaluates a route's {@code validate:} rules against the case's params (the execution
     * context the rules see) and returns the violations as the case's rows (roadmap Phase 19).
     * SQL rules run against the test datasource and record coverage like SQL-file cases.
     */
    private List<Map<String, Object>> evaluateValidation(TestCase test) {
        RouteFile route = route(test.validate().route());
        Path routeDir = route.source().getParent();
        List<ValidationRules.Rule> rules = new ArrayList<>();
        route.definition().validate().forEach((id, rule) -> {
            if (test.validate().rule() != null && !test.validate().rule().equals(id)) {
                return;
            }
            rules.add(compileRule(routeDir, id, rule));
        });
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Route '" + test.validate().route()
                    + "' declares no matching validation rule"
                    + (test.validate().rule() == null ? "" : " '" + test.validate().rule() + "'"));
        }
        try (Connection connection = dataSource.getConnection()) {
            return new ValidationRules(rules).evaluate(test.params(), connection,
                    (rule, bound) -> recordRuleCoverage(rule, bound));
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Validation SQL failed: " + ex.getMessage(), ex);
        }
    }

    private ValidationRules.Rule compileRule(Path routeDir, String id, ValidationRule rule) {
        if (rule.isExpression()) {
            return ValidationRules.expression(id, rule.when(), rule.rule(), rule.field(),
                    rule.code(), rule.message());
        }
        Path file = routeDir.resolve(rule.file()).normalize();
        return ValidationRules.sql(id, rule.when(), read(file), file.toString(), rule.params(),
                rule.field(), rule.code(), rule.message());
    }

    private void recordRuleCoverage(ValidationRules.Rule rule, BoundSql bound) {
        if (coverage != null && rule.sourcePath() != null) {
            String sqlId = appHome.relativize(Path.of(rule.sourcePath())).toString()
                    .replace('\\', '/');
            coverage.record(sqlId, bound.coverageTrace(), SqlCoverableLines.compute(rule.sql()));
        }
    }

    /**
     * Evaluates a route's {@code notify:} block or a job's notify steps against the case's
     * params (roadmap Phase 20), returning the fired notifications as rows — id, channel,
     * source, and the resolved payload columns — without touching SMTP or HTTP. Guards
     * ({@code when:}) and payload expressions evaluate exactly as they would at runtime.
     */
    private List<Map<String, Object>> evaluateNotify(TestCase test) {
        TestSuite.NotifyTarget target = test.notifications();
        if ((target.route() == null) == (target.job() == null)) {
            throw new IllegalArgumentException(
                    "A notify case needs exactly one of notify.route or notify.job");
        }
        List<io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify> compiled = new ArrayList<>();
        if (target.route() != null) {
            RouteFile route = route(target.route());
            route.definition().notifications().forEach((id, spec) -> {
                if (target.id() == null || target.id().equals(id)) {
                    compiled.add(io.tesseraql.yaml.notify.NotifyEvents
                            .compile(target.route(), id, spec));
                }
            });
        } else {
            io.tesseraql.yaml.manifest.JobFile job = job(target.job());
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().effectiveSteps()) {
                if (step.notification() == null
                        || (target.id() != null && !target.id().equals(step.id()))) {
                    continue;
                }
                compiled.add(io.tesseraql.yaml.notify.NotifyEvents
                        .compile(target.job(), step.id(), step.notification()));
            }
        }
        if (compiled.isEmpty()) {
            throw new IllegalArgumentException("'"
                    + (target.route() != null ? target.route() : target.job())
                    + "' declares no matching notification"
                    + (target.id() == null ? "" : " '" + target.id() + "'"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (CaptureServer capture = target.isSend() ? CaptureServer.start() : null) {
            for (io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification : compiled) {
                if (!notification.fires(test.params())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("notify", notification.id());
                row.put("channel", notification.channel());
                row.put("source", notification.source());
                Map<String, Object> payload = notification.resolvePayload(test.params());
                payload.forEach(row::putIfAbsent);
                if (capture != null) {
                    deliver(notification, payload, row, capture);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Real-send mode (docs/testing.md): the production senders build and deliver over a real
     * socket to the runner's capture servers — {@link io.tesseraql.yaml.notify.WebhookNotifier}
     * for webhook channels (JSON body, timestamp header, HMAC signature; rows add
     * {@code delivered}/{@code signature}/{@code wireBody}) and
     * {@link io.tesseraql.yaml.notify.MailNotifier} for mail channels (rendered template body,
     * inline subject, to/from resolution over real SMTP; rows add
     * {@code delivered}/{@code to}/{@code from}/{@code subject}/{@code wireBody}). Inbox
     * channels keep their evaluate-only row — delivery there is a database write the outbox
     * integration tests own.
     */
    private void deliver(io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification,
            Map<String, Object> payload, Map<String, Object> row, CaptureServer capture) {
        io.tesseraql.yaml.notify.NotificationChannels channels = io.tesseraql.yaml.notify.NotificationChannels
                .load(manifest.config());
        io.tesseraql.yaml.notify.NotificationChannels.Channel channel = channels
                .require(notification.channel());
        io.tesseraql.core.outbox.OutboxEvent event = new io.tesseraql.core.outbox.OutboxEvent(
                "test-" + java.util.UUID.randomUUID(), "notify", notification.id(), "notify",
                null, "PENDING", 0, null, java.time.Instant.now(), null, "test");
        io.tesseraql.yaml.notify.NotifyEvents.Envelope envelope = new io.tesseraql.yaml.notify.NotifyEvents.Envelope(
                notification.channel(), notification.source(), payload);
        if (io.tesseraql.yaml.notify.NotificationChannels.WEBHOOK.equals(channel.type())) {
            try {
                new io.tesseraql.yaml.notify.WebhookNotifier().send(channel, envelope, event,
                        capture.url());
                CaptureServer.Captured captured = capture.last();
                row.put("delivered", true);
                row.put("signature", captured.headers().get("x-tesseraql-signature"));
                row.put("wireBody", captured.body());
            } catch (Exception ex) {
                throw new IllegalStateException("Webhook real-send failed: " + ex.getMessage(),
                        ex);
            }
        } else if (io.tesseraql.yaml.notify.NotificationChannels.MAIL.equals(channel.type())) {
            deliverMail(channel, envelope, event, row);
        }
    }

    /** Mail real-send: the production sender delivers to an in-process SMTP capture. */
    private void deliverMail(io.tesseraql.yaml.notify.NotificationChannels.Channel channel,
            io.tesseraql.yaml.notify.NotifyEvents.Envelope envelope,
            io.tesseraql.core.outbox.OutboxEvent event, Map<String, Object> row) {
        com.icegreen.greenmail.util.GreenMail smtp = new com.icegreen.greenmail.util.GreenMail(
                new com.icegreen.greenmail.util.ServerSetup(0, "127.0.0.1", "smtp"));
        try {
            smtp.start();
            new io.tesseraql.yaml.notify.MailNotifier(appHome).send(channel, envelope, event,
                    "127.0.0.1", smtp.getSmtp().getPort());
            jakarta.mail.internet.MimeMessage[] received = smtp.getReceivedMessages();
            if (received.length == 0) {
                throw new IllegalStateException("No message reached the SMTP capture");
            }
            jakarta.mail.internet.MimeMessage message = received[received.length - 1];
            row.put("delivered", true);
            row.put("to", addresses(message.getRecipients(
                    jakarta.mail.Message.RecipientType.TO)));
            row.put("from", addresses(message.getFrom()));
            row.put("subject", message.getSubject());
            row.put("wireBody", String.valueOf(message.getContent()).trim());
        } catch (Exception ex) {
            throw new IllegalStateException("Mail real-send failed: " + ex.getMessage(), ex);
        } finally {
            smtp.stop();
        }
    }

    /**
     * Plans a job's {@code http-call:} steps against the case's params (roadmap Phase 26), without
     * issuing a network request: each matching step is one row carrying its id, method, the resolved
     * url and host, whether the host is allow-listed, and the credential name. URL placeholders and
     * query bindings resolve exactly as they would at runtime, so a case exercises the binding and
     * the deny-by-default egress rule deterministically.
     */
    private List<Map<String, Object>> evaluateHttpCall(TestCase test) {
        TestSuite.HttpCallTarget target = test.httpCall();
        boolean hasJob = target.job() != null && !target.job().isBlank();
        boolean hasRoute = target.route() != null && !target.route().isBlank();
        if (hasJob == hasRoute) {
            throw new IllegalArgumentException(
                    "An http-call case needs exactly one of http-call.job or http-call.route");
        }
        if (manifest == null) {
            manifest = new ManifestLoader().load(appHome);
        }
        io.tesseraql.yaml.http.HttpOutbound outbound = io.tesseraql.yaml.http.HttpOutbound
                .load(manifest.config());
        List<Map.Entry<String, io.tesseraql.yaml.model.HttpCallSpec>> calls = new ArrayList<>();
        if (hasJob) {
            io.tesseraql.yaml.manifest.JobFile job = job(target.job());
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().effectiveSteps()) {
                io.tesseraql.yaml.model.HttpCallSpec spec = step.httpCall();
                if (spec == null || (target.id() != null && !target.id().equals(step.id()))) {
                    continue;
                }
                calls.add(Map.entry(step.id(), spec));
            }
        } else {
            // A query route's http: sources plan the same way a job's steps do
            // (docs/connectors.md, "HTTP sources") — url, host, and the allow-list verdict.
            RouteFile route = route(target.route());
            route.definition().http().forEach((name, source) -> {
                if (target.id() == null || target.id().equals(name)) {
                    calls.add(Map.entry(name, source.toCall()));
                }
            });
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (target.isSend()) {
            // Real-send mode (docs/testing.md): the request — headers, credential, body — is
            // built exactly as at runtime and goes over a real socket to the runner's capture
            // server; the row carries what actually hit the wire.
            try (CaptureServer capture = CaptureServer.start()) {
                for (var call : calls) {
                    rows.add(sendRow(call.getKey(), call.getValue(), test.params(), outbound,
                            capture));
                }
            }
        } else {
            for (var call : calls) {
                rows.add(planRow(call.getKey(), call.getValue(), test.params(), outbound));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("'" + (hasJob ? target.job() : target.route())
                    + "' declares no matching http-call"
                    + (target.id() == null ? "" : " '" + target.id() + "'"));
        }
        return rows;
    }

    /**
     * One real-send row: everything the plan row reports, plus what actually crossed the wire
     * to the capture server — the method and path+query, the credential's Authorization (or
     * custom) header exactly as runtime delivery builds it, declared headers, and the body.
     */
    private Map<String, Object> sendRow(String id, io.tesseraql.yaml.model.HttpCallSpec spec,
            Map<String, Object> params, io.tesseraql.yaml.http.HttpOutbound outbound,
            CaptureServer capture) {
        Map<String, Object> row = planRow(id, spec, params, outbound);
        String url = (String) row.get("url");
        java.net.URI original = java.net.URI.create(url);
        String pathAndQuery = original.getRawPath()
                + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery());
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(capture.url() + pathAndQuery))
                .timeout(java.time.Duration.ofSeconds(10));
        spec.headers().forEach(request::header);
        if (spec.credential() != null && !spec.credential().isBlank()) {
            outbound.requireCredential(spec.credential()).authorizationHeaders()
                    .forEach(request::header);
        }
        request.method(spec.effectiveMethod(), spec.body() == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(spec.body()));
        try {
            java.net.http.HttpResponse<Void> response = java.net.http.HttpClient.newHttpClient()
                    .send(request.build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
            CaptureServer.Captured captured = capture.last();
            row.put("sent", true);
            row.put("requestPath", captured.pathAndQuery());
            row.put("authorization", captured.headers().get("authorization"));
            row.put("requestBody", captured.body());
            row.put("responseStatus", response.statusCode());
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Real-send failed: " + ex.getMessage(), ex);
        }
        return row;
    }

    /**
     * The runner's per-case HTTP capture server (docs/testing.md, real-send mode): a local
     * listener that records each request — method, path, headers, body — and answers 200, so a
     * suite exercises the true wire without any external dependency.
     */
    static final class CaptureServer implements AutoCloseable {

        /** One captured request; header names are lower-cased. */
        record Captured(String method, String pathAndQuery, Map<String, String> headers,
                String body) {
        }

        private final com.sun.net.httpserver.HttpServer server;
        private final List<Captured> requests = java.util.Collections
                .synchronizedList(new ArrayList<>());

        private CaptureServer(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        static CaptureServer start() {
            try {
                com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
                        .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                CaptureServer capture = new CaptureServer(server);
                server.createContext("/", exchange -> {
                    Map<String, String> headers = new LinkedHashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) -> headers
                            .put(name.toLowerCase(java.util.Locale.ROOT),
                                    String.join(",", values)));
                    String body = new String(exchange.getRequestBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    String query = exchange.getRequestURI().getRawQuery();
                    capture.requests.add(new Captured(exchange.getRequestMethod(),
                            exchange.getRequestURI().getRawPath()
                                    + (query == null ? "" : "?" + query),
                            headers, body));
                    byte[] ok = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, ok.length);
                    exchange.getResponseBody().write(ok);
                    exchange.close();
                });
                server.start();
                return capture;
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        Captured last() {
            if (requests.isEmpty()) {
                throw new IllegalStateException("No request reached the capture server");
            }
            return requests.get(requests.size() - 1);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** One planning row: the resolved url/host and the deny-by-default egress verdict. */
    private Map<String, Object> planRow(String id, io.tesseraql.yaml.model.HttpCallSpec spec,
            Map<String, Object> params, io.tesseraql.yaml.http.HttpOutbound outbound) {
        String url = resolveUrl(manifest.config(), spec, params);
        String host = hostOf(url);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("http", id);
        row.put("method", spec.effectiveMethod());
        row.put("url", url);
        row.put("host", host);
        row.put("allowed", host != null && outbound.isHostAllowed(host));
        row.put("credential", spec.credential());
        return row;
    }

    /** Resolves a step's url (config placeholders and bound query params) for a planning row. */
    private static String resolveUrl(io.tesseraql.yaml.config.AppConfig config,
            io.tesseraql.yaml.model.HttpCallSpec spec, Map<String, Object> params) {
        String raw = spec.url() == null ? "" : spec.url();
        String base;
        try {
            base = config.resolve(raw);
        } catch (RuntimeException ex) {
            base = raw;
        }
        if (spec.query().isEmpty()) {
            return base;
        }
        io.tesseraql.core.expr.EvaluationContext evaluation = new io.tesseraql.core.expr.EvaluationContext(
                params);
        List<String> pairs = new ArrayList<>();
        spec.query().forEach((name, sourceExpr) -> {
            Object value = evaluation.resolve(java.util.Arrays.asList(sourceExpr.split("\\.")));
            if (value != null) {
                pairs.add(encode(name) + "=" + encode(String.valueOf(value)));
            }
        });
        if (pairs.isEmpty()) {
            return base;
        }
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + String.join("&", pairs);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Resolves message-catalog keys for a locale and returns them as rows (roadmap Phase 22):
     * one row per key with {@code key}, {@code locale}, and {@code text} columns. Lookup reads
     * the app's {@code messages/<locale>.yml} catalogs with the same exact-tag-then-bare-language
     * walk as the runtime; an unresolvable key yields a null {@code text}, so an expectation on
     * it fails visibly.
     */
    private List<Map<String, Object>> evaluateMessages(TestCase test) {
        TestSuite.MessagesTarget target = test.messages();
        if (target.locale() == null || target.locale().isBlank()) {
            throw new IllegalArgumentException("A messages case needs a messages.locale tag");
        }
        io.tesseraql.yaml.i18n.MessageCatalog catalog = io.tesseraql.yaml.i18n.MessageCatalog
                .load(appHome.resolve("messages"));
        String tag = java.util.Locale.forLanguageTag(target.locale().trim()).toLanguageTag();
        List<String> keys = target.keys() == null || target.keys().isEmpty()
                ? catalog.forLocale(tag).keySet().stream().sorted().toList()
                : target.keys();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("locale", tag);
            row.put("text", catalog.resolve(tag, key));
            rows.add(row);
        }
        return rows;
    }

    private io.tesseraql.yaml.manifest.JobFile job(String jobId) {
        if (manifest == null) {
            manifest = new ManifestLoader().load(appHome);
        }
        return manifest.jobs().stream()
                .filter(job -> jobId.equals(job.definition().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown job '" + jobId + "' in notify case"));
    }

    private RouteFile route(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("A validation case needs a validate.route id");
        }
        if (manifest == null) {
            manifest = new ManifestLoader().load(appHome);
        }
        return manifest.routes().stream()
                .filter(route -> routeId.equals(route.definition().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown route '" + routeId + "' in validation case"));
    }

    /**
     * The outcome of executing a 2-way SQL file: the result rows of a query (including a write
     * with a {@code RETURNING} clause), or the affected-row count of a plain write — exactly one
     * of the two is non-null.
     */
    private record SqlOutcome(List<Map<String, Object>> rows, Integer updateCount) {
    }

    /** Executes a 2-way SQL file on the given (case-transaction) connection, recording coverage. */
    private SqlOutcome executeSql(Connection connection, Path sqlFile,
            Map<String, Object> params) {
        List<SqlNode> nodes = Sql2WayParser.parse(read(sqlFile));
        BoundSql bound = SqlRenderer.render(nodes, params);
        if (coverage != null) {
            coverage.record(appHome.relativize(sqlFile).toString().replace('\\', '/'),
                    bound.coverageTrace(), SqlCoverableLines.compute(nodes));
        }
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                BoundParameter parameter = bound.parameters().get(i);
                statement.setObject(i + 1, parameter.value());
            }
            if (statement.execute()) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return new SqlOutcome(readRows(resultSet), null);
                }
            }
            return new SqlOutcome(null, statement.getUpdateCount());
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("SQL execution failed: " + ex.getMessage(), ex);
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet)
            throws java.sql.SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                row.put(metaData.getColumnLabel(col), resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }

    private static boolean looselyEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    private static String stripIdentityPrefix(String contract) {
        return contract.startsWith("identity.")
                ? contract.substring("identity.".length())
                : contract;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
