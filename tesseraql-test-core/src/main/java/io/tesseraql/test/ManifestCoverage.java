package io.tesseraql.test;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Derives manifest-based coverage kinds from the app manifest and the declarative test suites
 * (design ch. 14): route, security, SAML, SCIM, and validation coverage. All of them reuse the
 * {@link ItemCoverage} model.
 *
 * <p>A route (and with it the security declaration it carries) counts as covered when a suite case
 * exercises one of the SQL artifacts the route executes — its {@code sql} file or contract, any
 * named {@code queries} entry, or the {@code import}/{@code export} SQL of a file-transfer route.
 * SAML coverage declares the Identity SQL Contracts the SAML login path executes when user linking
 * is enabled; SCIM coverage declares the contract SQL files wired through
 * {@code tesseraql.scim.*} configuration. A kind with nothing declared (for example SCIM coverage
 * in an app without SCIM) reports a 1.0 ratio.
 */
public final class ManifestCoverage {

    private static final List<String> SCIM_USER_OPS = List.of(
            "create", "findById", "list", "replace", "delete", "findByUserName", "count");
    private static final List<String> SCIM_GROUP_OPS = List.of(
            "create", "findById", "list", "replace", "delete",
            "listMembers", "addMember", "removeMember", "count");

    private ManifestCoverage() {
    }

    /** Route coverage: every manifest route declared, routes whose SQL artifacts ran covered. */
    public static ItemCoverage routes(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("route", manifest, suites, definition -> true);
    }

    /** Security coverage: routes declaring a {@code security:} block, covered like routes. */
    public static ItemCoverage security(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("security", manifest, suites, definition -> definition.security() != null);
    }

    /**
     * Validation coverage (roadmap Phase 19): every rule of every route's {@code validate:}
     * block is declared as {@code <routeId>.<ruleId>}, and a suite's validation case covers the
     * rules it evaluates — the targeted rule, or the route's whole block when no rule is named.
     */
    public static ItemCoverage validation(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("validation");
        for (RouteFile route : manifest.routes()) {
            RouteDefinition definition = route.definition();
            if (definition.id() == null) {
                continue;
            }
            definition.validate().keySet()
                    .forEach(ruleId -> coverage.declare(definition.id() + "." + ruleId));
        }
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.validate() == null || test.validate().route() == null) {
                    continue;
                }
                String prefix = test.validate().route() + ".";
                String targeted = test.validate().rule();
                coverage.declared().stream()
                        .filter(item -> item.startsWith(prefix))
                        .filter(item -> targeted == null || item.equals(prefix + targeted))
                        .forEach(coverage::cover);
            }
        }
        return coverage;
    }

    /**
     * Notification coverage (roadmap Phase 20): every notification of every route's
     * {@code notify:} block is declared as {@code <routeId>.<notifyId>}, and every job notify
     * step as {@code <jobId>.<stepId>}; a suite's notify case covers the declarations it
     * evaluates — the targeted one, or the route's/job's whole set when no id is named.
     */
    public static ItemCoverage notification(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("notification");
        for (RouteFile route : manifest.routes()) {
            RouteDefinition definition = route.definition();
            if (definition.id() == null) {
                continue;
            }
            definition.notifications().keySet()
                    .forEach(notifyId -> coverage.declare(definition.id() + "." + notifyId));
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            if (job.definition().id() == null) {
                continue;
            }
            job.definition().effectiveSteps().stream()
                    .filter(step -> step.notification() != null)
                    .forEach(step -> coverage.declare(job.definition().id() + "." + step.id()));
        }
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                TestSuite.NotifyTarget target = test.notifications();
                if (target == null) {
                    continue;
                }
                String owner = target.route() != null ? target.route() : target.job();
                if (owner == null) {
                    continue;
                }
                String prefix = owner + ".";
                coverage.declared().stream()
                        .filter(item -> item.startsWith(prefix))
                        .filter(item -> target.id() == null || item.equals(prefix + target.id()))
                        .forEach(coverage::cover);
            }
        }
        return coverage;
    }

    /**
     * Document coverage (roadmap Phase 21): every route exporting a printable document
     * ({@code format: pdf} on {@code query-export}/{@code file-export}) is declared and covered
     * like routes - when a suite case exercises one of its SQL artifacts, the extraction the
     * document renders is proven.
     */
    public static ItemCoverage document(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("document", manifest, suites,
                definition -> definition.fileExport() != null
                        && "pdf".equals(definition.fileExport().format()));
    }

    /**
     * SAML coverage: when SAML user linking is enabled ({@code tesseraql.saml.link.enabled}), the
     * SAML login path resolves the principal through Identity SQL Contracts — those contracts are
     * declared and contract test cases cover them. Without SAML (or without linking) the login
     * path executes no app-authored SQL, so nothing is declared.
     */
    public static ItemCoverage saml(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("saml");
        AppConfig config = manifest.config();
        if (flag(config, "tesseraql.saml.enabled") && flag(config, "tesseraql.saml.link.enabled")) {
            coverage.declareAll(List.of(IdentityContracts.FIND_USER_BY_LOGIN,
                    IdentityContracts.FIND_GROUPS_BY_USER_ID,
                    IdentityContracts.FIND_ROLES_BY_USER_ID,
                    IdentityContracts.FIND_PERMISSIONS_BY_USER_ID));
            if (flag(config, "tesseraql.saml.link.provision")) {
                coverage.declare(IdentityContracts.CREATE_USER);
            }
            testedContracts(suites).stream().filter(coverage.declared()::contains)
                    .forEach(coverage::cover);
        }
        return coverage;
    }

    /**
     * SCIM coverage: the contract SQL files wired through {@code tesseraql.scim.users.*} (and
     * {@code tesseraql.scim.groups.*} when group provisioning is on) are declared per operation,
     * and a SQL test case exercising the configured file covers it.
     */
    public static ItemCoverage scim(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("scim");
        AppConfig config = manifest.config();
        if (!flag(config, "tesseraql.scim.enabled")) {
            return coverage;
        }
        Set<Path> tested = testedSqlPaths(manifest.appHome(), suites);
        declareScimOps(coverage, manifest, "users", SCIM_USER_OPS, tested);
        if (flag(config, "tesseraql.scim.groups.enabled")) {
            declareScimOps(coverage, manifest, "groups", SCIM_GROUP_OPS, tested);
        }
        return coverage;
    }

    private static void declareScimOps(ItemCoverage coverage, AppManifest manifest, String resource,
            List<String> operations, Set<Path> testedPaths) {
        for (String operation : operations) {
            String item = resource + "." + operation;
            manifest.config().getString("tesseraql.scim." + item).ifPresent(sqlFile -> {
                coverage.declare(item);
                if (testedPaths.contains(manifest.appHome().resolve(sqlFile).normalize())) {
                    coverage.cover(item);
                }
            });
        }
    }

    private static ItemCoverage routeKind(String kind, AppManifest manifest, List<TestSuite> suites,
            Predicate<RouteDefinition> declared) {
        ItemCoverage coverage = new ItemCoverage(kind);
        Set<Path> testedPaths = testedSqlPaths(manifest.appHome(), suites);
        Set<String> testedContracts = testedContracts(suites);
        for (RouteFile route : manifest.routes()) {
            RouteDefinition definition = route.definition();
            if (definition.id() == null || !declared.test(definition)) {
                continue;
            }
            coverage.declare(definition.id());
            if (exercised(route, testedPaths, testedContracts)) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    /**
     * Whether any of the route's SQL artifacts was exercised by a test case. SQL file bindings are
     * route-file relative (like the compiler resolves them), test case files are app-home relative.
     */
    private static boolean exercised(RouteFile route, Set<Path> testedPaths,
            Set<String> testedContracts) {
        Path routeDir = route.source().getParent();
        for (SqlBinding binding : bindings(route.definition())) {
            if (binding.file() != null
                    && testedPaths.contains(routeDir.resolve(binding.file()).normalize())) {
                return true;
            }
            if (binding.contract() != null
                    && testedContracts.contains(stripIdentityPrefix(binding.contract()))) {
                return true;
            }
        }
        return false;
    }

    private static List<SqlBinding> bindings(RouteDefinition definition) {
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

    private static Set<Path> testedSqlPaths(Path appHome, List<TestSuite> suites) {
        Set<Path> paths = new LinkedHashSet<>();
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.sql() != null && test.sql().file() != null) {
                    paths.add(appHome.resolve(test.sql().file()).normalize());
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

    private static String stripIdentityPrefix(String contract) {
        return contract.startsWith("identity.")
                ? contract.substring("identity.".length())
                : contract;
    }

    private static boolean flag(AppConfig config, String key) {
        return config.getString(key).map(Boolean::parseBoolean).orElse(false);
    }
}
