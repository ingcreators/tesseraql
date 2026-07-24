package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.SecuritySpec;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the effective security posture of every gallery app after the route files stopped
 * restating what the path-matched security defaults provide (docs/route-defaults.md): loading
 * the manifest must yield an explicit auth mode on every HTTP route, with the API surface
 * bearer and the page surface browser. A route this test finds unauthenticated is a route the
 * defaults silently stopped covering — exactly the regression it exists to catch.
 */
class ExampleAppSecurityPostureTest {

    /** The two hand-declared public shells: static skeletons whose data fragments authenticate. */
    private static final Set<String> PUBLIC_SHELLS = Set.of(
            "user-admin-app:/users", "scaffold-demo-app:/");

    private static final Set<String> BEARER_APPS = Set.of(
            "helpdesk-app", "purchase-request-app");

    @ParameterizedTest
    @ValueSource(strings = {"user-admin-app", "scaffold-demo-app", "inventory-app",
            "helpdesk-app", "purchase-request-app"})
    void everyRouteResolvesAnExplicitAuthMode(String app) {
        Path home = Paths.get("..", "examples", app).toAbsolutePath().normalize();
        AppManifest manifest = new ManifestLoader().load(home);
        assertThat(manifest.routes()).isNotEmpty();

        for (RouteFile route : manifest.routes()) {
            SecuritySpec security = route.definition().security();
            assertThat(security).as("%s %s has no effective security", app, route.urlPath())
                    .isNotNull();
            String expected = expectedAuth(app, route.urlPath());
            assertThat(security.auth())
                    .as("%s %s %s", app, route.httpMethod(), route.urlPath())
                    .isEqualTo(expected);
            if ("browser".equals(expected) && !"GET".equals(route.httpMethod())) {
                assertThat(security.csrf())
                        .as("%s %s %s must enforce CSRF", app, route.httpMethod(),
                                route.urlPath())
                        .isTrue();
            }
        }
    }

    private static String expectedAuth(String app, String urlPath) {
        if (PUBLIC_SHELLS.contains(app + ":" + urlPath)) {
            return "public";
        }
        if (BEARER_APPS.contains(app) || urlPath.startsWith("/api")) {
            return "bearer";
        }
        return "browser";
    }
}
