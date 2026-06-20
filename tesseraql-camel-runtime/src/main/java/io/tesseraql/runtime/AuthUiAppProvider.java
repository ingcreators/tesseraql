package io.tesseraql.runtime;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled login page ({@code GET /_tesseraql/login}) that backs the admin console's
 * browser-session auth. Mounted by default; turn it off with
 * {@code tesseraql.console.login.enabled: false} (or {@code tesseraql.apps.auth-ui.enabled: false}).
 *
 * <p>The credential endpoints — {@code POST /_tesseraql/login} and {@code GET /_tesseraql/logout} —
 * are wired by the runtime ({@code LoginRouteBuilder}); this app only serves the page, reading which
 * sign-in methods are available from the {@code auth.loginMethods} service.
 */
public final class AuthUiAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        boolean enabled = config.getString("tesseraql.console.login.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        if (!enabled) {
            return List.of();
        }
        return List.of(new ClasspathAppSource(
                "auth-ui", "tesseraql/apps/auth-ui", getClass().getClassLoader()));
    }
}
