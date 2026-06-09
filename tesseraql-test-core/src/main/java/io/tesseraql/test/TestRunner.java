package io.tesseraql.test;

import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.TestCase;
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
 * Runs a declarative {@link TestSuite} against a database, executing SQL files and Identity SQL
 * Contracts and checking the result rows (design ch. 13, 13.2).
 */
public final class TestRunner {

    private final DataSource dataSource;
    private final Path appHome;
    private final IdentityService identity;
    private final RealmConfig realm;
    private final SqlCoverage coverage;

    public TestRunner(DataSource dataSource, Path appHome) {
        this(dataSource, appHome, null, null, null);
    }

    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity, RealmConfig realm) {
        this(dataSource, appHome, identity, realm, null);
    }

    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity, RealmConfig realm,
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
        if (test.contract() != null && !test.contract().isBlank()) {
            if (identity == null || realm == null) {
                throw new IllegalStateException("Contract tests require an identity service and realm");
            }
            return identity.execute(realm, stripIdentityPrefix(test.contract()), test.params());
        }
        if (test.sql() == null || test.sql().file() == null) {
            throw new IllegalArgumentException("Test '" + test.name() + "' has no sql or contract");
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
                            + "' expected " + entry.getValue() + " but was " + actual.get(entry.getKey()));
                }
            }
        }
        return TestResult.pass(test.name());
    }

    private List<Map<String, Object>> executeSql(Path sqlFile, Map<String, Object> params) {
        BoundSql bound = SqlRenderer.render(read(sqlFile), params);
        if (coverage != null) {
            coverage.record(appHome.relativize(sqlFile).toString().replace('\\', '/'),
                    bound.coverageTrace());
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

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws java.sql.SQLException {
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
        return contract.startsWith("identity.") ? contract.substring("identity.".length()) : contract;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
