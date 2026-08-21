package io.tesseraql.runtime;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled account surface ({@code /_tesseraql/account}, roadmap Phase 48,
 * design in docs/account.md): the signed-in user's profile and — as later slices land —
 * language, theme, notification, and session self-service. On by default exactly when the
 * bundled login page is (no login, no session, no account); turn it off with
 * {@code tesseraql.apps.account.enabled: false} for apps that own the surface themselves.
 */
public final class AccountAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        if (!enabled(config)) {
            return List.of();
        }
        return List.of(new ClasspathAppSource(
                "account", "tesseraql/apps/account", getClass().getClassLoader()));
    }

    /** The one source of truth for the surface's enablement — the runtime wiring reads it too. */
    public static boolean enabled(AppConfig config) {
        boolean login = config.getString("tesseraql.console.login.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        return config.getString("tesseraql.apps.account.enabled")
                .map(Boolean::parseBoolean).orElse(login);
    }
}
