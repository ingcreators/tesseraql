package io.tesseraql.cli;

import io.tesseraql.apptasks.AppMigrator;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql migrate [operation] --app <dir>}: applies or inspects the app's
 * {@code db/migration} Flyway scripts (design ch. 18, 31) — the CLI-native form of the
 * {@code tesseraql:migrate} goal, over the shared {@link AppMigrator}. {@code apply} (the default)
 * is the non-serving form of the runtime's mount-time migrations; {@code info}, {@code validate}
 * and {@code repair} cover inspection and recovery for CI and operators.
 */
@Command(name = "migrate", description = "Apply/info/validate/repair the app's db/migration scripts.")
final class MigrateCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "apply (default), info, validate, or repair.")
    String operation = "apply";

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    CliDatasource datasource;

    @Option(names = {"--datasource"}, description = "Migration set to act on (default: main).")
    String datasourceName = "main";

    @Option(names = {
            "--app-name"}, description = "App name keying the history table (default: the app directory name).")
    String appName;

    @Override
    public Integer call() throws Exception {
        AppConfig config = new ManifestLoader().load(app).config();
        DriverManagerDataSource dataSource = datasource.resolve(config);
        String name = appName != null && !appName.isBlank()
                ? appName
                : app.toAbsolutePath().normalize().getFileName().toString();

        switch (operation.toLowerCase(Locale.ROOT)) {
            case "apply" -> AppMigrator.migrate(app, name, datasourceName, dataSource)
                    .ifPresentOrElse(
                            result -> System.out.println("Applied " + result.applied()
                                    + " migration(s) for app " + name + ", datasource "
                                    + datasourceName + " (history table " + result.historyTable()
                                    + ")"),
                            this::logNoDirectory);
            case "info" -> AppMigrator.info(app, name, datasourceName, dataSource).ifPresentOrElse(
                    info -> {
                        info.migrations().forEach(migration -> System.out.printf("%-12s %-10s %s%n",
                                migration.version(), migration.state(), migration.description()));
                        System.out.println("TesseraQL migrate info: " + info.migrations().size()
                                + " migration(s) in " + info.historyTable());
                    },
                    this::logNoDirectory);
            case "validate" -> {
                AppMigrator.ValidateResult result = AppMigrator
                        .validate(app, name, datasourceName, dataSource).orElse(null);
                if (result == null) {
                    logNoDirectory();
                } else if (result.valid()) {
                    System.out.println("TesseraQL migrate validate: valid (" + result.historyTable()
                            + ")");
                } else {
                    result.problems().forEach(System.err::println);
                    System.err.println("TesseraQL migrate validate failed: "
                            + result.problems().size() + " problem(s)");
                    return 1;
                }
            }
            case "repair" -> AppMigrator.repair(app, name, datasourceName, dataSource)
                    .ifPresentOrElse(
                            summary -> System.out.println("TesseraQL migrate repair: removed "
                                    + summary.removed() + ", deleted " + summary.deleted()
                                    + ", aligned " + summary.aligned() + " ("
                                    + summary.historyTable() + ")"),
                            this::logNoDirectory);
            default -> {
                System.err.println("Unknown migrate operation '" + operation
                        + "'; expected apply, info, validate, or repair");
                return 2;
            }
        }
        return 0;
    }

    private void logNoDirectory() {
        System.out.println("No migration directory for datasource '" + datasourceName + "' in "
                + app + "; nothing to do");
    }
}
