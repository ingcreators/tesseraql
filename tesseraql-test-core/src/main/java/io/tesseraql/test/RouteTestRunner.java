package io.tesseraql.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.test.RouteSuite.RouteCase;
import io.tesseraql.test.RouteSuite.RouteExpect;
import io.tesseraql.test.TestReport.TestResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs declarative {@link RouteSuite} HTTP tests against a running runtime (design ch. 13.1) and
 * returns a {@link TestReport}.
 */
public final class RouteTestRunner {

    private static final TqlErrorCode PARSE_ERROR = new TqlErrorCode(TqlDomain.YAML, 1402);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public RouteTestRunner(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public static RouteSuite parse(String yaml) {
        try {
            return YAML.readValue(yaml, RouteSuite.class);
        } catch (Exception ex) {
            throw new TqlException(PARSE_ERROR, "Failed to parse route suite: " + ex.getMessage());
        }
    }

    public TestReport run(RouteSuite suite) {
        List<TestResult> results = new ArrayList<>();
        for (RouteCase test : suite.tests()) {
            results.add(runCase(test));
        }
        return new TestReport(results);
    }

    private TestResult runCase(RouteCase test) {
        try {
            HttpResponse<String> response = send(test);
            return assertExpectation(test, response);
        } catch (Exception ex) {
            return TestResult.fail(test.name(), ex.getMessage());
        }
    }

    private HttpResponse<String> send(RouteCase test) throws Exception {
        HttpRequest.BodyPublisher bodyPublisher = test.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(test.body());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + test.path()))
                .method(test.effectiveMethod(), bodyPublisher);
        test.headers().forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private TestResult assertExpectation(RouteCase test, HttpResponse<String> response) {
        RouteExpect expect = test.expect();
        if (expect == null) {
            return TestResult.pass(test.name());
        }
        if (expect.status() != null && response.statusCode() != expect.status()) {
            return TestResult.fail(test.name(),
                    "expected status " + expect.status() + " but was " + response.statusCode());
        }
        for (String fragment : expect.bodyContains()) {
            if (!response.body().contains(fragment)) {
                return TestResult.fail(test.name(), "body did not contain '" + fragment + "'");
            }
        }
        return TestResult.pass(test.name());
    }
}
