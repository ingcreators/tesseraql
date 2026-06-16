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
                "db/migration/V1__create_items.sql",
                "templates/nav.html",
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
                "README.md");
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
                .contains("tesseraql-bom").contains("tesseraql-maven-plugin");
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
