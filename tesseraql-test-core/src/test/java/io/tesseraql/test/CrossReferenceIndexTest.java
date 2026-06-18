package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.SqlTarget;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestIndex;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Focused unit test for the reusable {@link CrossReferenceIndex}. The broader behaviour the index
 * powers is asserted through {@code ManifestCoverageTest}; this test pins the linkage primitives
 * the documentation portal also depends on.
 */
class CrossReferenceIndexTest {

    private static final Path APP_HOME = Path.of("/app").toAbsolutePath().normalize();
    private static final SimpleYamlParser PARSER = new SimpleYamlParser();

    private static RouteFile route(String relativeYmlPath, String yaml) {
        return new RouteFile("get", "/" + relativeYmlPath,
                APP_HOME.resolve(relativeYmlPath), PARSER.parseRoute(yaml, relativeYmlPath));
    }

    private static AppManifest manifest() {
        return new AppManifest(APP_HOME, new AppConfig(Map.of(), name -> null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                ManifestIndex.of(Map.of(), "test"));
    }

    private static CrossReferenceIndex index(TestSuite... suites) {
        return CrossReferenceIndex.of(manifest(), List.of(suites));
    }

    private static TestSuite sqlSuite(String... sqlFiles) {
        return new TestSuite(java.util.Arrays.stream(sqlFiles)
                .map(file -> new TestCase("tests " + file, new SqlTarget(file), null, Map.of(),
                        null, null, null, null))
                .toList());
    }

    private static TestSuite contractSuite(String... contracts) {
        return new TestSuite(java.util.Arrays.stream(contracts)
                .map(contract -> new TestCase("tests " + contract, null, contract, Map.of(),
                        null, null, null, null))
                .toList());
    }

    private static final String SEARCH_ROUTE = """
            version: tesseraql/v1
            id: users.search
            kind: route
            recipe: query-json
            sql:
              file: search.sql
            """;

    @Test
    void exercisesResolvesSqlBindingsRouteFileRelativeAgainstAppHomeRelativeCases() {
        RouteFile route = route("web/api/users/get.yml", SEARCH_ROUTE);
        CrossReferenceIndex index = index(sqlSuite("web/api/users/search.sql"));

        // The case path is app-home relative; the binding is route-file relative — they must meet.
        assertThat(index.testedSqlPaths())
                .containsExactly(APP_HOME.resolve("web/api/users/search.sql"));
        assertThat(index.exercises(route)).isTrue();
        // A case in a different directory resolves to a different absolute path and does not match.
        assertThat(index(sqlSuite("web/api/other/search.sql")).exercises(route)).isFalse();
        assertThat(index().exercises(route)).isFalse();
    }

    @Test
    void exercisesMatchesContractBindingsStrippingTheIdentityPrefix() {
        RouteFile route = route("web/api/admin/post.yml", """
                version: tesseraql/v1
                id: admin.create
                kind: route
                recipe: command-json
                sql:
                  contract: identity.create-user
                """);

        // A contract case omits the identity. prefix the binding carries; both sides strip it.
        assertThat(index(contractSuite("identity.create-user")).testedContracts())
                .containsExactly("create-user");
        assertThat(index(contractSuite("identity.create-user")).exercises(route)).isTrue();
        assertThat(index(contractSuite("create-user")).exercises(route)).isTrue();
        assertThat(index(contractSuite("identity.find-user-by-login")).exercises(route)).isFalse();
    }

    @Test
    void exercisesCountsNamedQueryBindingsNotJustTheMainSql() {
        RouteFile route = route("web/api/users/get.yml", """
                version: tesseraql/v1
                id: users.list
                kind: route
                recipe: query-html
                sql:
                  file: list.sql
                queries:
                  total:
                    file: count.sql
                response:
                  html:
                    template: list.html
                """);

        // A suite that only touches the secondary query SQL still exercises the route.
        assertThat(index(sqlSuite("web/api/users/count.sql")).exercises(route)).isTrue();
    }

    @Test
    void casesForCollectsCasesLinkedBySqlContractValidateRouteAndNotifyRoute() {
        RouteFile route = route("web/api/users/get.yml", SEARCH_ROUTE);
        TestSuite suite = new TestSuite(List.of(
                new TestCase("by sql", new SqlTarget("web/api/users/search.sql"), null, Map.of(),
                        null, null, null, null),
                new TestCase("by validate", null, null, Map.of(), null,
                        new TestSuite.ValidateTarget("users.search", null), null, null),
                new TestCase("by notify", null, null, Map.of(), null, null,
                        new TestSuite.NotifyTarget("users.search", null, null), null),
                new TestCase("unrelated route", null, null, Map.of(), null,
                        new TestSuite.ValidateTarget("other.route", null), null, null),
                new TestCase("unrelated sql", new SqlTarget("web/other/x.sql"), null, Map.of(),
                        null, null, null, null)));
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest(), List.of(suite));

        // SQL/contract bindings and id-based validate/notify targets all link; others do not.
        assertThat(index.casesFor(route)).extracting(TestCase::name)
                .containsExactly("by sql", "by validate", "by notify");
    }

    @Test
    void bindingsGathersTheMainSqlAndEveryNamedQuery() {
        RouteDefinition definition = PARSER.parseRoute("""
                version: tesseraql/v1
                id: users.list
                kind: route
                recipe: query-html
                sql:
                  file: list.sql
                queries:
                  total:
                    file: count.sql
                response:
                  html:
                    template: list.html
                """, "web/api/users/get.yml");

        assertThat(CrossReferenceIndex.bindings(definition))
                .extracting(SqlBinding::file)
                .containsExactlyInAnyOrder("list.sql", "count.sql");
    }

    @Test
    void stripIdentityPrefixRemovesOnlyTheIdentityNamespace() {
        assertThat(CrossReferenceIndex.stripIdentityPrefix("identity.create-user"))
                .isEqualTo("create-user");
        assertThat(CrossReferenceIndex.stripIdentityPrefix("create-user"))
                .isEqualTo("create-user");
        assertThat(CrossReferenceIndex.stripIdentityPrefix("scim.users.create"))
                .isEqualTo("scim.users.create");
    }
}
