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
        if (test.sql() == null || test.sql().file() == null) {
            throw new IllegalArgumentException(
                    "Test '" + test.name() + "' has no sql, contract, or validate target");
        }
        return executeSql(appHome.resolve(test.sql().file()), test.params());
    }

    private TestResult assertExpectation(TestCase test, List<Map<String, Object>> rows) {
        Expectation expect = test.expect();
        if (expect == null) {
            return TestResult.pass(test.name());
        }
        if (expect.rowCount() != null && rows.size() != expect.rowCount()) {
            return TestResult.fail(test.name(),
                    "expected rowCount " + expect.rowCount() + " but was " + rows.size());
        }
        for (int i = 0; i < expect.rows().size(); i++) {
            if (i >= rows.size()) {
                return TestResult.fail(test.name(), "expected at least " + (i + 1) + " rows");
            }
            Map<String, Object> actual = rows.get(i);
            for (Map.Entry<String, Object> entry : expect.rows().get(i).entrySet()) {
                if (!looselyEqual(actual.get(entry.getKey()), entry.getValue())) {
                    return TestResult.fail(test.name(), "row " + i + " field '" + entry.getKey()
                            + "' expected " + entry.getValue() + " but was "
                            + actual.get(entry.getKey()));
                }
            }
        }
        return TestResult.pass(test.name());
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
        for (io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification : compiled) {
            if (!notification.fires(test.params())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("notify", notification.id());
            row.put("channel", notification.channel());
            row.put("source", notification.source());
            notification.resolvePayload(test.params()).forEach(row::putIfAbsent);
            rows.add(row);
        }
        return rows;
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

    private List<Map<String, Object>> executeSql(Path sqlFile, Map<String, Object> params) {
        List<SqlNode> nodes = Sql2WayParser.parse(read(sqlFile));
        BoundSql bound = SqlRenderer.render(nodes, params);
        if (coverage != null) {
            coverage.record(appHome.relativize(sqlFile).toString().replace('\\', '/'),
                    bound.coverageTrace(), SqlCoverableLines.compute(nodes));
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                BoundParameter parameter = bound.parameters().get(i);
                statement.setObject(i + 1, parameter.value());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
            }
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
