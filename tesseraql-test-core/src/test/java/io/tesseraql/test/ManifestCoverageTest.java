package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.test.TestSuite.SqlTarget;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestIndex;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestCoverageTest {

    private static final Path APP_HOME = Path.of("/app").toAbsolutePath().normalize();
    private static final SimpleYamlParser PARSER = new SimpleYamlParser();

    private static RouteFile route(String relativeYmlPath, String yaml) {
        return new RouteFile("get", "/" + relativeYmlPath,
                APP_HOME.resolve(relativeYmlPath), PARSER.parseRoute(yaml, relativeYmlPath));
    }

    private static AppManifest manifest(Map<String, Object> config, RouteFile... routes) {
        return new AppManifest(APP_HOME, new AppConfig(config, name -> null),
                List.of(routes), List.of(), ManifestIndex.of(Map.of(), "test"));
    }

    private static TestSuite sqlSuite(String... sqlFiles) {
        return new TestSuite(java.util.Arrays.stream(sqlFiles)
                .map(file -> new TestCase("tests " + file, new SqlTarget(file), null, Map.of(),
                        null, null, null))
                .toList());
    }

    private static TestSuite contractSuite(String... contracts) {
        return new TestSuite(java.util.Arrays.stream(contracts)
                .map(contract -> new TestCase("tests " + contract, null, contract, Map.of(),
                        null, null, null))
                .toList());
    }

    private static final String SEARCH_ROUTE = """
            version: tesseraql/v1
            id: users.search
            kind: route
            recipe: query-json
            security:
              auth: bearer
              policy: users.read
            sql:
              file: search.sql
            """;

    private static final String PUBLIC_ROUTE = """
            version: tesseraql/v1
            id: health.echo
            kind: route
            recipe: query-json
            sql:
              file: echo.sql
            """;

    @Test
    void routeCoverageLinksTestedSqlFilesToRouteFileRelativeBindings() {
        AppManifest manifest = manifest(Map.of(),
                route("web/api/users/get.yml", SEARCH_ROUTE),
                route("web/health/get.yml", PUBLIC_ROUTE));
        ItemCoverage coverage = ManifestCoverage.routes(manifest,
                List.of(sqlSuite("web/api/users/search.sql")));

        assertThat(coverage.kind()).isEqualTo("route");
        assertThat(coverage.declared()).containsExactlyInAnyOrder("health.echo", "users.search");
        assertThat(coverage.covered()).containsExactlyInAnyOrder("users.search");
        assertThat(coverage.uncovered()).containsExactlyInAnyOrder("health.echo");
    }

    @Test
    void securityCoverageDeclaresOnlyRoutesWithASecurityBlock() {
        AppManifest manifest = manifest(Map.of(),
                route("web/api/users/get.yml", SEARCH_ROUTE),
                route("web/health/get.yml", PUBLIC_ROUTE));
        ItemCoverage coverage = ManifestCoverage.security(manifest,
                List.of(sqlSuite("web/api/users/search.sql")));

        assertThat(coverage.declared()).containsExactlyInAnyOrder("users.search");
        assertThat(coverage.ratio()).isEqualTo(1.0);
    }

    @Test
    void routeCoverageLinksContractBindingsToContractCases() {
        AppManifest manifest = manifest(Map.of(), route("web/api/admin/post.yml", """
                version: tesseraql/v1
                id: admin.create
                kind: route
                recipe: command-json
                sql:
                  contract: identity.create-user
                """));
        ItemCoverage covered = ManifestCoverage.routes(manifest,
                List.of(contractSuite("identity.create-user")));
        ItemCoverage uncovered = ManifestCoverage.routes(manifest,
                List.of(contractSuite("identity.find-user-by-login")));

        assertThat(covered.covered()).containsExactlyInAnyOrder("admin.create");
        assertThat(uncovered.covered()).isEmpty();
    }

    private static final String VALIDATED_ROUTE = """
            version: tesseraql/v1
            id: members.register
            kind: route
            recipe: command-json
            validate:
              dateOrder:
                rule: body.endDate >= body.startDate
                field: endDate
              uniqueEmail:
                file: check-email.sql
                field: email
            sql:
              file: insert-member.sql
              mode: update
            """;

    private static TestSuite validateSuite(String route, String rule) {
        return new TestSuite(List.of(new TestCase("validates " + route, null, null, Map.of(),
                null, new TestSuite.ValidateTarget(route, rule), null)));
    }

    @Test
    void validationCoverageDeclaresEveryRuleAndTracksEvaluatedOnes() {
        AppManifest manifest = manifest(Map.of(),
                route("web/members/post.yml", VALIDATED_ROUTE),
                route("web/api/users/get.yml", SEARCH_ROUTE));

        ItemCoverage all = ManifestCoverage.validation(manifest,
                List.of(validateSuite("members.register", null)));
        ItemCoverage one = ManifestCoverage.validation(manifest,
                List.of(validateSuite("members.register", "uniqueEmail")));
        ItemCoverage none = ManifestCoverage.validation(manifest, List.of());

        assertThat(all.kind()).isEqualTo("validation");
        assertThat(all.declared()).containsExactlyInAnyOrder(
                "members.register.dateOrder", "members.register.uniqueEmail");
        // A case without a rule filter evaluates the route's whole block.
        assertThat(all.covered()).containsExactlyInAnyOrder(
                "members.register.dateOrder", "members.register.uniqueEmail");
        assertThat(one.covered()).containsExactlyInAnyOrder("members.register.uniqueEmail");
        assertThat(none.covered()).isEmpty();
        assertThat(none.uncovered()).hasSize(2);
    }

    @Test
    void validationCoverageIsEmptyWithoutValidateBlocks() {
        AppManifest manifest = manifest(Map.of(), route("web/api/users/get.yml", SEARCH_ROUTE));

        ItemCoverage coverage = ManifestCoverage.validation(manifest, List.of());

        assertThat(coverage.declared()).isEmpty();
        assertThat(coverage.ratio()).isEqualTo(1.0);
    }

    private static final String NOTIFYING_ROUTE = """
            version: tesseraql/v1
            id: members.register
            kind: route
            recipe: command-json
            notify:
              confirmation:
                channel: member-mail
                payload:
                  email: body.email
              audit:
                channel: audit-webhook
            sql:
              file: insert-member.sql
              mode: update
            """;

    private static io.tesseraql.yaml.manifest.JobFile notifyingJob() {
        return new io.tesseraql.yaml.manifest.JobFile(APP_HOME.resolve("batch/cleanup/job.yml"),
                new io.tesseraql.yaml.model.JobDefinition("tesseraql/v1", "members.cleanup",
                        "job", "batch-pipeline", null, Map.of(), null,
                        List.of(
                                new io.tesseraql.yaml.model.PipelineStep("purge",
                                        new io.tesseraql.yaml.model.SqlBinding("purge.sql", null,
                                                "update", Map.of(), null, null, null, null, null)),
                                new io.tesseraql.yaml.model.PipelineStep("report", null,
                                        new io.tesseraql.yaml.model.NotifySpec("ops-mail", null,
                                                Map.of()))),
                        false));
    }

    private static TestSuite notifySuite(String route, String job, String id) {
        return new TestSuite(List.of(new TestCase("notifies", null, null, Map.of(),
                null, null, new TestSuite.NotifyTarget(route, job, id))));
    }

    @Test
    void notificationCoverageDeclaresRouteAndJobNotificationsAndTracksEvaluatedOnes() {
        AppManifest manifest = new AppManifest(APP_HOME, new AppConfig(Map.of(), name -> null),
                List.of(route("web/members/post.yml", NOTIFYING_ROUTE)),
                List.of(notifyingJob()), ManifestIndex.of(Map.of(), "test"));

        ItemCoverage all = ManifestCoverage.notification(manifest,
                List.of(notifySuite("members.register", null, null)));
        ItemCoverage one = ManifestCoverage.notification(manifest,
                List.of(notifySuite("members.register", null, "audit")));
        ItemCoverage jobOnly = ManifestCoverage.notification(manifest,
                List.of(notifySuite(null, "members.cleanup", null)));
        ItemCoverage none = ManifestCoverage.notification(manifest, List.of());

        assertThat(all.kind()).isEqualTo("notification");
        // Routes declare <routeId>.<notifyId>; job notify steps declare <jobId>.<stepId> —
        // the purge SQL step is not a notification and is not declared.
        assertThat(all.declared()).containsExactlyInAnyOrder(
                "members.register.confirmation", "members.register.audit",
                "members.cleanup.report");
        assertThat(all.covered()).containsExactlyInAnyOrder(
                "members.register.confirmation", "members.register.audit");
        assertThat(one.covered()).containsExactlyInAnyOrder("members.register.audit");
        assertThat(jobOnly.covered()).containsExactlyInAnyOrder("members.cleanup.report");
        assertThat(none.covered()).isEmpty();
    }

    @Test
    void samlCoverageDeclaresLinkContractsOnlyWhenLinkingIsEnabled() {
        AppManifest linked = manifest(Map.of("tesseraql", Map.of("saml", Map.of(
                "enabled", "true",
                "link", Map.of("enabled", "true", "provision", "true")))));
        ItemCoverage coverage = ManifestCoverage.saml(linked,
                List.of(contractSuite("identity.find-user-by-login", "identity.create-user")));

        assertThat(coverage.declared()).containsExactlyInAnyOrder("create-user",
                "find-groups-by-user-id",
                "find-permissions-by-user-id", "find-roles-by-user-id", "find-user-by-login");
        assertThat(coverage.covered()).containsExactlyInAnyOrder("create-user",
                "find-user-by-login");

        AppManifest unlinked = manifest(
                Map.of("tesseraql", Map.of("saml", Map.of("enabled", "true"))));
        assertThat(ManifestCoverage.saml(unlinked, List.of()).declared()).isEmpty();
        assertThat(ManifestCoverage.saml(unlinked, List.of()).ratio()).isEqualTo(1.0);
    }

    @Test
    void scimCoverageDeclaresConfiguredContractFilesAndTracksTestedOnes() {
        AppManifest manifest = manifest(Map.of("tesseraql", Map.of("scim", Map.of(
                "enabled", "true",
                "users", Map.of(
                        "create", "scim/users-create.sql",
                        "findById", "scim/users-find.sql")))));
        ItemCoverage coverage = ManifestCoverage.scim(manifest,
                List.of(sqlSuite("scim/users-create.sql")));

        assertThat(coverage.declared()).containsExactlyInAnyOrder("users.create", "users.findById");
        assertThat(coverage.covered()).containsExactlyInAnyOrder("users.create");

        AppManifest disabled = manifest(Map.of());
        assertThat(ManifestCoverage.scim(disabled, List.of()).declared()).isEmpty();
        assertThat(ManifestCoverage.scim(disabled, List.of()).ratio()).isEqualTo(1.0);
    }

    @Test
    void scimGroupOperationsRequireTheGroupsFlag() {
        Map<String, Object> scim = Map.of(
                "enabled", "true",
                "groups", Map.of(
                        "enabled", "true",
                        "create", "scim/groups-create.sql"));
        AppManifest manifest = manifest(Map.of("tesseraql", Map.of("scim", scim)));

        assertThat(ManifestCoverage.scim(manifest, List.of()).declared())
                .containsExactlyInAnyOrder("groups.create");
    }
}
