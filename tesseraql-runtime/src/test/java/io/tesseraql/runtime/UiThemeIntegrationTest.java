package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The app's accent axis and its own token stylesheet (docs/hypermedia-ui.md "UI defaults" and
 * "Custom themes"): {@code tesseraql.ui.color} sets the kit's {@code data-color}, and
 * {@code tesseraql.ui.stylesheet} links a theme builder export out of the app's assets after
 * the kit's own token sheets, which is what lets an app switch to a generated theme without
 * the framework carrying a single color of its own.
 *
 * <p>Asserted on the sign-in page, which every deployment serves before anything is
 * authenticated and which links the token layer exactly as the app shell does.
 */
@Testcontainers
class UiThemeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String VENDOR = "/assets/vendor/hypermedia-components__core/dist/hc.tokens.color-";

    static TesseraqlRuntime builtIn;
    static TesseraqlRuntime generated;
    static TesseraqlRuntime refused;
    static TesseraqlRuntime stock;
    static Path builtInHome;
    static Path generatedHome;
    static Path refusedHome;
    static Path stockHome;

    @BeforeAll
    static void start() throws Exception {
        builtInHome = appHome("ui-builtin", """
                    color: teal
                """);
        generatedHome = appHome("ui-generated", """
                    color: brand
                    stylesheet: theme/brand.css
                """);
        // An axis name that is not an axis name, and a path that climbs out of the asset root.
        refusedHome = appHome("ui-refused", """
                    color: "Not An Axis"
                    stylesheet: ../../../etc/passwd.css
                """);
        stockHome = appHome("ui-stock", """
                    color: default
                """);
        Files.createDirectories(generatedHome.resolve("assets/theme"));
        Files.writeString(generatedHome.resolve("assets/theme/brand.css"),
                "@layer hc.tokens { [data-color=\"brand\"] { --hc-button-primary-bg: #7c3aed; } }\n");
        builtIn = TesseraqlRuntime.start(builtInHome, 0);
        generated = TesseraqlRuntime.start(generatedHome, 0);
        refused = TesseraqlRuntime.start(refusedHome, 0);
        stock = TesseraqlRuntime.start(stockHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : new TesseraqlRuntime[]{builtIn, generated, refused,
                stock}) {
            if (runtime != null) {
                runtime.close();
            }
        }
        for (Path home : new Path[]{builtInHome, generatedHome, refusedHome, stockHome}) {
            deleteRecursively(home);
        }
    }

    /** A built-in axis is the attribute plus the token sheet the kit ships for it. */
    @Test
    void aBuiltInAccentLinksTheKitsOwnSheet() throws Exception {
        String page = signInPage(builtIn);

        assertThat(page).contains("data-color=\"teal\"").contains(VENDOR + "teal.css");
        // Orthogonal axes: the accent does not disturb the neutral ramp or the density.
        assertThat(page).contains("data-neutral=\"slate\"")
                .contains("hc.tokens.neutral-slate.css");
    }

    /**
     * A theme builder accent is the attribute and the app's own sheet — the kit ships no axis
     * by that name, so linking one would 404, and the generated block is what defines it.
     */
    @Test
    void aGeneratedAccentLinksTheAppsOwnSheetInstead() throws Exception {
        String page = signInPage(generated);

        assertThat(page).contains("data-color=\"brand\"")
                .contains("href=\"/assets/theme/brand.css\"")
                .doesNotContain(VENDOR);
        // The app's sheet is the last of the token layer: the kit's sheets come first, so a
        // generated block overrides them inside the same @layer hc.tokens.
        assertThat(page.indexOf("hc.tokens.neutral-slate.css"))
                .isLessThan(page.indexOf("/assets/theme/brand.css"));
        // And it is served, not merely linked.
        assertThat(get(generated, "/assets/theme/brand.css").statusCode()).isEqualTo(200);
    }

    /** Neither value is shaped like what it names, so the page renders as if neither were set. */
    @Test
    void valuesThatAreNotAnAxisOrAnAssetAreIgnored() throws Exception {
        String page = signInPage(refused);

        assertThat(page).doesNotContain("data-color=").doesNotContain(VENDOR)
                .doesNotContain("passwd.css");
    }

    /**
     * Naming the kit's own accent renders nothing: {@code default} is what hc.min.css already
     * carries, so there is no attribute to set and no sheet to link on top of it.
     */
    @Test
    void namingTheDefaultAccentRendersNothing() throws Exception {
        String page = signInPage(stock);

        assertThat(page).contains("hc.min.css").doesNotContain("data-color=")
                .doesNotContain(VENDOR);
    }

    private static String signInPage(TesseraqlRuntime runtime) throws Exception {
        HttpResponse<String> response = get(runtime, "/_tesseraql/login");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static HttpResponse<String> get(TesseraqlRuntime runtime, String path)
            throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path appHome(String name, String ui) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-" + name + "-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  ui:
                %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(name, ui.stripTrailing(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        return target;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
    }
}
