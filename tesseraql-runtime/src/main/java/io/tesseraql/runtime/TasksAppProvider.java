package io.tesseraql.runtime;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled task queue ({@code /_tesseraql/tasks}, docs/workflow-surface.md
 * decision 6): the signed-in user's open workflow tasks of this application.
 *
 * <p>Mounted by the runtime that serves the application — a hosted member included, the shape
 * the exports page takes (docs/job-inbox.md decision 4): a workflow task is the application's
 * business data on its own datasource, and the detail pages the queue links into are the
 * application's routes. The page used to ride the account app, which a hosted member never
 * mounts, so under a stack it answered at the origin against the origin's own datasource and
 * listed no member's tasks (docs/job-inbox.md, filed). On by default exactly when the bundled
 * login page is; {@code tesseraql.apps.tasks.enabled: false} turns it off.
 */
public final class TasksAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        if (!enabled(config)) {
            return List.of();
        }
        return List.of(new ClasspathAppSource(
                "tasks", "tesseraql/apps/tasks", getClass().getClassLoader()));
    }

    /** The one source of truth for the page's enablement — the account surface's rule. */
    public static boolean enabled(AppConfig config) {
        boolean login = config.getString("tesseraql.console.login.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        return config.getString("tesseraql.apps.tasks.enabled")
                .map(Boolean::parseBoolean).orElse(login);
    }
}
