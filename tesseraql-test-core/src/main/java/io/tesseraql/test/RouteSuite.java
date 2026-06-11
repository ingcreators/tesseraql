package io.tesseraql.test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A declarative route (HTTP) test suite (design ch. 13.1). Each case issues an HTTP request to a
 * running runtime and asserts on the response status and body.
 *
 * @param tests the route test cases
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteSuite(List<RouteCase> tests) {

    public RouteSuite {
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /**
     * A single route test case.
     *
     * @param name    case name
     * @param method  HTTP method (default GET)
     * @param path    request path
     * @param headers request headers
     * @param body    request body, when applicable
     * @param expect  the expectation
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteCase(String name, String method, String path,
            Map<String, String> headers, String body, RouteExpect expect) {

        public RouteCase {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public String effectiveMethod() {
            return method == null || method.isBlank() ? "GET" : method.toUpperCase();
        }
    }

    /**
     * Assertions on the HTTP response.
     *
     * @param status       expected status code, or null to skip
     * @param bodyContains substrings that must all appear in the response body
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteExpect(Integer status, List<String> bodyContains) {

        public RouteExpect {
            bodyContains = bodyContains == null ? List.of() : List.copyOf(bodyContains);
        }
    }
}
