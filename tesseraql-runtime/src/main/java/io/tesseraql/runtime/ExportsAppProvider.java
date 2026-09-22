package io.tesseraql.runtime;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled "My exports" page ({@code /_tesseraql/exports}, docs/job-inbox.md
 * decision 4): the signed-in user's exports of this application, as the job card per row.
 *
 * <p>Mounted by the runtime that serves the application — a hosted member included, unlike the
 * account surface, which is the stack's and answers once at the origin: a transfer's record is
 * the application's, in the {@code operations} component on its own datasource, beside the
 * routes whose subtree serves the file. On by default exactly when the bundled login page is
 * (no login, no session, no page); {@code tesseraql.apps.exports.enabled: false} turns it off.
 */
public final class ExportsAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        if (!enabled(config)) {
            return List.of();
        }
        return List.of(new ClasspathAppSource(
                "exports", "tesseraql/apps/exports", getClass().getClassLoader()));
    }

    /** The one source of truth for the page's enablement — the account surface's rule. */
    public static boolean enabled(AppConfig config) {
        boolean login = config.getString("tesseraql.console.login.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        return config.getString("tesseraql.apps.exports.enabled")
                .map(Boolean::parseBoolean).orElse(login);
    }
}
