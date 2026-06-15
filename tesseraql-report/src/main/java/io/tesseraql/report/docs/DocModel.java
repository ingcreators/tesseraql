package io.tesseraql.report.docs;

import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecModel;
import java.util.List;

/**
 * The full spec-layer documentation model for an application (documentation portal v1): every
 * route's {@link RouteSpec} with the declarative test cases that cover it attached, plus the
 * migration listing. {@code AppDocGenerator} builds it by wrapping the yaml-side
 * {@code RouteSpecGenerator} and joining each route to its tests through the shared
 * {@code CrossReferenceIndex}. It is the spec part of the portal; the report overlay (test results,
 * coverage) and schema introspection are later layers.
 *
 * @param routes     the route docs, in the {@code RouteSpecGenerator} order (path then method)
 * @param migrations the migration listing
 */
public record DocModel(List<RouteDoc> routes, List<RouteSpecModel.Migration> migrations) {

    public DocModel {
        routes = List.copyOf(routes);
        migrations = List.copyOf(migrations);
    }

    /**
     * One route's reference: its spec plus the test cases that exercise it.
     *
     * @param route the route spec
     * @param tests the covering test cases, in suite then declaration order
     */
    public record RouteDoc(RouteSpec route, List<TestCaseDoc> tests) {

        public RouteDoc {
            tests = List.copyOf(tests);
        }
    }

    /**
     * A declarative test case projected to the facts the portal shows: its name, what kind of case
     * it is, and what it targets.
     *
     * @param name   the case name
     * @param kind   {@code sql}, {@code contract}, {@code validation}, {@code notification},
     *               {@code http-call}, {@code messages}, or {@code unknown}
     * @param target the targeted SQL file, contract, {@code route[.rule]}, {@code owner[.id]}, or
     *               locale — {@code null} when the case declares none
     */
    public record TestCaseDoc(String name, String kind, String target) {
    }
}
