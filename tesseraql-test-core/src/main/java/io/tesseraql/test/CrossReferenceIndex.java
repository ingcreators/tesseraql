package io.tesseraql.test;

import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The reusable test&rarr;route&rarr;SQL cross-reference index: which SQL artifacts a declarative
 * test suite exercises, and whether a given route (or any manifest item carrying a
 * {@link RouteDefinition}) executes one of them.
 *
 * <p>This linkage is the basis of the manifest coverage kinds derived by {@link ManifestCoverage}
 * (which delegates here) and of the application documentation portal, which lifts the same
 * page&rarr;SQL&rarr;test linkage into per-route reference pages rather than recomputing it.
 *
 * <p>A test case exercises a route when it targets the route's {@code sql} file (resolved
 * route-file relative, as the compiler resolves bindings), any named {@code queries}/{@code steps}
 * entry, the {@code import}/{@code export} SQL of a file-transfer route, or the Identity SQL
 * Contract one of those bindings runs (the {@code identity.} prefix a binding uses but a contract
 * test case omits is stripped on both sides). A case's {@code verify:} read-back steps exercise
 * their files the same way the case's target does — they execute the same SQL.
 */
public final class CrossReferenceIndex {

    private final Path appHome;
    private final List<TestSuite> suites;
    private final Set<Path> testedSqlPaths;
    private final Set<String> testedContracts;

    private CrossReferenceIndex(Path appHome, List<TestSuite> suites, Set<Path> testedSqlPaths,
            Set<String> testedContracts) {
        this.appHome = appHome;
        this.suites = suites;
        this.testedSqlPaths = testedSqlPaths;
        this.testedContracts = testedContracts;
    }

    /** Builds the index over an application's manifest home and its declarative test suites. */
    public static CrossReferenceIndex of(AppManifest manifest, List<TestSuite> suites) {
        return new CrossReferenceIndex(manifest.appHome(), List.copyOf(suites),
                testedSqlPaths(manifest.appHome(), suites), testedContracts(suites));
    }

    /** The app-home-relative SQL file paths a {@code sql:} test case targets. */
    public Set<Path> testedSqlPaths() {
        return Collections.unmodifiableSet(testedSqlPaths);
    }

    /** The Identity SQL Contract names a {@code contract:} test case targets, prefix stripped. */
    public Set<String> testedContracts() {
        return Collections.unmodifiableSet(testedContracts);
    }

    /** Whether any of the route's SQL artifacts was exercised by a test case. */
    public boolean exercises(RouteFile route) {
        return exercises(route.source().getParent(), route.definition());
    }

    /**
     * Whether any of the definition's SQL artifacts was exercised by a test case. SQL file bindings
     * are route-file relative (like the compiler resolves them); test case files are app-home
     * relative.
     *
     * @param routeDir   the directory of the YAML file declaring the definition
     * @param definition the route, tool, resource, or UI-resource definition to test
     */
    public boolean exercises(Path routeDir, RouteDefinition definition) {
        for (SqlBinding binding : bindings(definition)) {
            if (binding.file() != null
                    && testedSqlPaths.contains(routeDir.resolve(binding.file()).normalize())) {
                return true;
            }
            if (binding.contract() != null
                    && testedContracts.contains(stripIdentityPrefix(binding.contract()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The declarative test cases that cover the route: those exercising one of its SQL artifacts
     * (by SQL file or Identity SQL Contract) and those targeting it by id through a
     * {@code validate.route} or {@code notify.route} case. The portal attaches these to each route's
     * reference page; order follows suite then in-suite declaration order.
     */
    public List<TestCase> casesFor(RouteFile route) {
        return casesFor(route.definition().id(), route.source().getParent(), route.definition());
    }

    /**
     * The test cases covering the given route, addressed by id, directory, and definition.
     *
     * @param routeId    the route id matched by {@code validate.route}/{@code notify.route} cases
     * @param routeDir   the directory of the YAML file declaring the route
     * @param definition the route definition whose SQL bindings are matched
     */
    public List<TestCase> casesFor(String routeId, Path routeDir, RouteDefinition definition) {
        Set<Path> boundPaths = new LinkedHashSet<>();
        Set<String> boundContracts = new LinkedHashSet<>();
        for (SqlBinding binding : bindings(definition)) {
            if (binding.file() != null) {
                boundPaths.add(routeDir.resolve(binding.file()).normalize());
            }
            if (binding.contract() != null) {
                boundContracts.add(stripIdentityPrefix(binding.contract()));
            }
        }
        List<TestCase> cases = new ArrayList<>();
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (links(test, routeId, boundPaths, boundContracts)) {
                    cases.add(test);
                }
            }
        }
        return cases;
    }

    private boolean links(TestCase test, String routeId, Set<Path> boundPaths,
            Set<String> boundContracts) {
        if (test.sql() != null && test.sql().file() != null
                && boundPaths.contains(appHome.resolve(test.sql().file()).normalize())) {
            return true;
        }
        for (TestSuite.VerifyStep step : test.verify()) {
            if (step.sql() != null && step.sql().file() != null
                    && boundPaths.contains(appHome.resolve(step.sql().file()).normalize())) {
                return true;
            }
        }
        if (test.contract() != null && !test.contract().isBlank()
                && boundContracts.contains(stripIdentityPrefix(test.contract()))) {
            return true;
        }
        if (routeId == null) {
            return false;
        }
        return (test.validate() != null && routeId.equals(test.validate().route()))
                || (test.notifications() != null && routeId.equals(test.notifications().route()));
    }

    /**
     * Every SQL binding a route executes: its main {@code sql}, declared {@code steps} and
     * {@code queries}, and the {@code import}/{@code export} (and export {@code after}) SQL of a
     * file-transfer route.
     */
    public static List<SqlBinding> bindings(RouteDefinition definition) {
        List<SqlBinding> bindings = new ArrayList<>();
        if (definition.sql() != null) {
            bindings.add(definition.sql());
        }
        bindings.addAll(definition.steps().values());
        bindings.addAll(definition.queries().values());
        if (definition.fileImport() != null && definition.fileImport().sql() != null) {
            bindings.add(definition.fileImport().sql());
        }
        if (definition.fileExport() != null) {
            if (definition.fileExport().sql() != null) {
                bindings.add(definition.fileExport().sql());
            }
            if (definition.fileExport().after() != null
                    && definition.fileExport().after().sql() != null) {
                bindings.add(definition.fileExport().after().sql());
            }
        }
        return bindings;
    }

    /** Strips the {@code identity.} prefix a route binding carries but a contract test case omits. */
    public static String stripIdentityPrefix(String contract) {
        return contract.startsWith("identity.")
                ? contract.substring("identity.".length())
                : contract;
    }

    private static Set<Path> testedSqlPaths(Path appHome, List<TestSuite> suites) {
        Set<Path> paths = new LinkedHashSet<>();
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.sql() != null && test.sql().file() != null) {
                    paths.add(appHome.resolve(test.sql().file()).normalize());
                }
                for (TestSuite.VerifyStep step : test.verify()) {
                    if (step.sql() != null && step.sql().file() != null) {
                        paths.add(appHome.resolve(step.sql().file()).normalize());
                    }
                }
            }
        }
        return paths;
    }

    private static Set<String> testedContracts(List<TestSuite> suites) {
        Set<String> contracts = new LinkedHashSet<>();
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.contract() != null && !test.contract().isBlank()) {
                    contracts.add(stripIdentityPrefix(test.contract()));
                }
            }
        }
        return contracts;
    }
}
