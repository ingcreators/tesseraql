package io.tesseraql.yaml.migration;

import io.tesseraql.yaml.app.ApplicationName;
import io.tesseraql.yaml.config.AppConfig;

/**
 * The name keying an application's Flyway history table, resolved from its own configuration.
 *
 * <p>Declarative on purpose. The value was previously derived by each entry point from whatever it
 * happened to have — the runtime from {@code tesseraql.app.name}, {@code tesseraql migrate} from the
 * application's directory name, the Maven goal from {@code ${project.artifactId}} — and those never
 * agreed: measured across the bundled examples, the directory name and the application name differ
 * in every one. So migrating from the CLI wrote a history the runtime ignored and re-ran everything
 * under its own. Reading it from the application means every entry point picks up the same answer
 * without being told, and the CLI flag that used to hand-correct the disagreement is gone.
 *
 * <p>{@code tesseraql.migrations.historyName} exists for the case
 * {@link io.tesseraql.core.migration.SchemaHistory#requireFits} refuses: an application whose name
 * overflows the database's identifier limit shortens the history key rather than itself.
 */
public final class SchemaHistoryName {

    private SchemaHistoryName() {
    }

    /**
     * {@code tesseraql.migrations.historyName} when declared, otherwise the application's name.
     *
     * <p>There is no third branch: {@link ApplicationName} refuses an application without one, so
     * an unnamed application cannot reach a history table for another unnamed application to
     * collide with.
     */
    public static String of(AppConfig config) {
        return config.getString("tesseraql.migrations.historyName")
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .orElseGet(() -> ApplicationName.of(config));
    }
}
