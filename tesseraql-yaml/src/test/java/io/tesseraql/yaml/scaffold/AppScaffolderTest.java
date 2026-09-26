package io.tesseraql.yaml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code tesseraql new} skeleton (roadmap Phase 23): runnable, loadable, parseable. */
class AppScaffolderTest {

    private final AppScaffolder scaffolder = new AppScaffolder();

    @Test
    void skeletonContainsManifestConfigMigrationAndSmokeSuite() {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");

        assertThat(files).extracting(ScaffoldedFile::path).containsExactly(
                "config/application.yml",
                "config/tesseraql.yml",
                "config/env/prod.yml",
                "config/env/staging.yml",
                "db/migration/V1__create_items.sql",
                "templates/nav.html",
                "config/menu.yml",
                "web/get.yml",
                "web/index.html",
                "web/api/items/get.yml",
                "web/api/items/search.sql",
                "tests/smoke-test.yml",
                ".gitignore",
                "pom.xml",
                "mvnw",
                "mvnw.cmd",
                ".mvn/wrapper/maven-wrapper.properties",
                "compose.yaml",
                "README.md",
                ".vscode/tesseraql-defs-v1.schema.json",
                ".vscode/tesseraql-route-v1.schema.json",
                ".vscode/tesseraql-job-v1.schema.json",
                ".vscode/tesseraql-view-v1.schema.json",
                ".vscode/tesseraql-document-v1.schema.json",
                ".vscode/tesseraql-domains-v1.schema.json",
                ".vscode/tesseraql-rules-v1.schema.json",
                ".vscode/tesseraql-decisions-v1.schema.json",
                ".vscode/tesseraql-calendars-v1.schema.json",
                ".vscode/tesseraql-catalogs-v1.schema.json",
                ".vscode/tesseraql-config-v1.schema.json",
                ".vscode/tesseraql-tests-v1.schema.json",
                ".vscode/tesseraql-bench-v1.schema.json",
                ".vscode/tesseraql-messages-v1.schema.json",
                ".vscode/settings.json",
                ".vscode/extensions.json");
    }

    @Test
    void skeletonEmitsTheMavenWrapperAndStudioConfig() {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");

        // The script-only Maven Wrapper makes the Maven path need only a JDK.
        assertThat(content(files, "mvnw")).startsWith("#!");
        assertThat(content(files, ".mvn/wrapper/maven-wrapper.properties"))
                .contains("distributionUrl=");
        // The wrapper POM imports the BOM and binds the plugin.
        assertThat(content(files, "pom.xml"))
                .contains("tesseraql-bom").contains("tesseraql-maven-plugin")
                // The wrapper POM pins the resolved framework version, not the literal placeholder.
                .contains("<tesseraql.version>").doesNotContain("__TQL_VERSION__");
        // Studio is configured (on for local; env-gated for production).
        assertThat(content(files, "config/tesseraql.yml"))
                .contains("studio:").contains("TESSERAQL_STUDIO_ENABLED");
    }

    @Test
    void skeletonLoadsAsAManifestWithParseableRoutes(@TempDir Path target) {
        Path home = target.resolve("demo-app");
        scaffolder.writeNew(home, scaffolder.scaffold("demo-app"));

        AppManifest manifest = new ManifestLoader().load(home);
        assertThat(manifest.config().getString("tesseraql.app.name")).contains("demo-app");
        assertThat(manifest.routes()).extracting(RouteFile::urlPath)
                .containsExactlyInAnyOrder("/", "/api/items");
        // The starter routes reference the starter policies the config defines (the policy
        // names are dotted, so they are keys of the policies map, not nested paths).
        Object policies = manifest.config().navigate("tesseraql.security.policies");
        assertThat(policies).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> policyMap = (java.util.Map<String, Object>) policies;
        assertThat(policyMap.keySet()).containsExactlyInAnyOrder("app.read", "app.write");
    }

    /**
     * The deployed profiles separate the pools (docs/capacity-defaults.md decision 7): prod and
     * staging carry one layout, and the base configuration the development loop runs has none of
     * it.
     */
    @Test
    void theDeployedProfilesSeparateThePoolsAndTheBaseDoesNot(@TempDir Path target) {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");
        String prod = content(files, "config/env/prod.yml");
        String staging = content(files, "config/env/staging.yml");
        assertThat(prod).contains("TESSERAQL_ENV=prod");
        assertThat(staging).contains("TESSERAQL_ENV=staging");
        assertThat(layout(staging)).as("staging rehearses production").isEqualTo(layout(prod));
        assertThat(content(files, "config/tesseraql.yml"))
                .doesNotContain("jobPool", "fileTransferPool", "minimumIdle");

        scaffolder.writeNew(target, files);
        System.setProperty("tesseraql.env", "prod");
        try {
            io.tesseraql.yaml.config.AppConfig config = new ManifestLoader().load(target).config();
            String main = "tesseraql.datasources.main.";
            assertThat(config.getString(main + "maximumPoolSize")).hasValue("10");
            assertThat(config.getString(main + "connectionTimeoutMillis")).hasValue("10000");
            assertThat(config.getString(main + "jobPool.maximumPoolSize")).hasValue("3");
            assertThat(config.getString(main + "jobPool.minimumIdle")).hasValue("0");
            assertThat(config.getString(main + "fileTransferPool.maximumPoolSize"))
                    .hasValue("5");
            assertThat(config.getString(main + "fileTransferPool.minimumIdle")).hasValue("0");
        } finally {
            System.clearProperty("tesseraql.env");
        }
    }

    /**
     * Production cannot run on the development secret (docs/deployment-decisions.md decision 1):
     * the deployed profiles take it from {@code JWT_SECRET} with no fallback, and only the base
     * configuration, which the development loop runs, falls back to the published one.
     */
    @Test
    void theDeployedProfilesTakeTheJwtSecretWithNoFallback() {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");
        for (String profile : List.of("config/env/prod.yml", "config/env/staging.yml")) {
            assertThat(content(files, profile)).as(profile)
                    .contains("secret: ${JWT_SECRET}")
                    .doesNotContain(AppScaffolder.DEVELOPMENT_JWT_SECRET);
        }
        assertThat(content(files, "config/tesseraql.yml"))
                .contains("secret: ${JWT_SECRET:" + AppScaffolder.DEVELOPMENT_JWT_SECRET + "}");
    }

    /** A profile without its leading comment block: the configuration it carries. */
    private static String layout(String profile) {
        return profile.substring(profile.indexOf("tesseraql:"));
    }

    @Test
    void skeletonNamesFollowTheAppName() {
        List<ScaffoldedFile> files = scaffolder.scaffold("order-entry");

        String application = content(files, "config/application.yml");
        assertThat(application).contains("jdbc:postgresql://localhost:5432/order_entry");
        String tesseraql = content(files, "config/tesseraql.yml");
        assertThat(tesseraql).contains("name: order-entry");
        assertThat(content(files, "web/index.html")).contains("Welcome to order-entry");
    }

    @Test
    void starterSearchRouteParsesAndCoversBothBranches() {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");

        new SimpleYamlParser().parseRoute(content(files, "web/api/items/get.yml"), "get.yml");
        assertThat(content(files, "web/api/items/search.sql")).contains("/*%if q != null");
        String suite = content(files, "tests/smoke-test.yml");
        assertThat(suite).contains("q: \"\"").contains("q: First item");
    }

    @Test
    void rejectsInvalidAppNames() {
        assertThatThrownBy(() -> scaffolder.scaffold("Bad Name"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-5203");
    }

    @Test
    void refusesToWriteIntoANonEmptyTarget(@TempDir Path target) throws Exception {
        Files.writeString(target.resolve("existing.txt"), "occupied");

        assertThatThrownBy(() -> scaffolder.writeNew(target, scaffolder.scaffold("demo-app")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-5203");
    }

    private static String content(List<ScaffoldedFile> files, String path) {
        return files.stream().filter(file -> file.path().equals(path)).findFirst()
                .orElseThrow().content();
    }
}
