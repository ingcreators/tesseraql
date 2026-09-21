package io.tesseraql.yaml.bench;

import io.tesseraql.yaml.manifest.RouteFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a bench scenario may ask of an application (docs/deployment-maturity.md decision 8), held
 * in one place so the linter and the verb refuse the same things with the same codes.
 */
public final class BenchScenarios {

    /** TQL-YAML-1414: a bench request names a route the application does not declare. */
    public static final String UNKNOWN_ROUTE = "TQL-YAML-1414";

    /** TQL-YAML-1415: a bench request passes a param the route's {@code input:} does not declare. */
    public static final String UNDECLARED_PARAM = "TQL-YAML-1415";

    /**
     * TQL-YAML-1416: a bench request drives a route that is not GET or HEAD and the scenario does
     * not declare {@code writes: allowed}.
     */
    public static final String WRITE_NOT_ALLOWED = "TQL-YAML-1416";

    /** One refusal: the code and the sentence. */
    public record Problem(String code, String message) {
    }

    private BenchScenarios() {
    }

    /** The problems a scenario has against the routes an application declares; empty when none. */
    public static List<Problem> validate(BenchScenario scenario, Collection<RouteFile> routes) {
        Map<String, RouteFile> byId = new HashMap<>();
        for (RouteFile route : routes) {
            byId.putIfAbsent(route.definition().id(), route);
        }
        List<Problem> problems = new ArrayList<>();
        for (BenchScenario.Request request : scenario.requests()) {
            RouteFile route = byId.get(request.route());
            if (route == null) {
                problems.add(new Problem(UNKNOWN_ROUTE, "Bench request names route '"
                        + request.route() + "', which the application does not declare"
                        + " (tesseraql routes lists the ids)"));
                continue;
            }
            for (String param : request.params().keySet()) {
                if (!route.definition().input().containsKey(param)) {
                    problems.add(new Problem(UNDECLARED_PARAM, "Bench request for route '"
                            + request.route() + "' passes '" + param
                            + "', which the route's input: does not declare"));
                }
            }
            if (isWrite(route) && !scenario.writesAllowed()) {
                problems.add(new Problem(WRITE_NOT_ALLOWED, "Bench request drives '"
                        + request.route() + "' (" + route.httpMethod()
                        + "), a write, and the scenario does not declare writes: allowed —"
                        + " a load run that mutates data is a decision the author writes down"));
            }
        }
        return problems;
    }

    /** A route that is not GET or HEAD mutates, whatever its recipe. */
    public static boolean isWrite(RouteFile route) {
        String method = route.httpMethod();
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
    }
}
