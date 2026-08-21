package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.SecuritySpec;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the effective security posture of every bundled app after their route files stopped
 * restating what their own {@code security.defaults.routes} provide (docs/route-defaults.md):
 * the mount loads each app with the standard {@link ManifestLoader} against the app's own
 * config, so loading the module resource tree here resolves exactly what the runtime serves. A
 * route this test finds unauthenticated is a route the defaults silently stopped covering.
 */
class BundledAppSecurityPostureTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "tesseraql-studio/src/main/resources/tesseraql/apps/studio",
            "tesseraql-identity/src/main/resources/tesseraql/apps/iam-admin",
            "tesseraql-ops-ui/src/main/resources/tesseraql/apps/ops-console",
            "tesseraql-runtime/src/main/resources/tesseraql/apps/account",
            "tesseraql-runtime/src/main/resources/tesseraql/apps/auth-ui",
            "tesseraql-runtime/src/main/resources/tesseraql/apps/portal"})
    void everyBundledRouteResolvesAnExplicitAuthMode(String resourceRoot) {
        Path home = Paths.get("..").resolve(resourceRoot).toAbsolutePath().normalize();
        AppManifest manifest = new ManifestLoader().load(home);
        assertThat(manifest.routes()).isNotEmpty();

        for (RouteFile route : manifest.routes()) {
            SecuritySpec security = route.definition().security();
            assertThat(security)
                    .as("%s %s has no effective security", resourceRoot, route.urlPath())
                    .isNotNull();
            assertThat(security.auth())
                    .as("%s %s %s", resourceRoot, route.httpMethod(), route.urlPath())
                    .isIn("browser", "public");
            if ("browser".equals(security.auth()) && !"GET".equals(route.httpMethod())) {
                assertThat(security.csrfEnforced(route.httpMethod()))
                        .as("%s %s %s must enforce CSRF", resourceRoot, route.httpMethod(),
                                route.urlPath())
                        .isTrue();
            }
        }
    }
}
