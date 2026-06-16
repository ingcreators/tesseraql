package io.tesseraql.maven;

import io.tesseraql.apptasks.AppMigrator;
import io.tesseraql.report.DriverManagerDataSource;
import java.io.File;
import java.util.Locale;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Applies or inspects the app's {@code db/migration} Flyway scripts (design ch. 18). The
 * {@code apply} operation is the pre-deploy form of the migrations the runtime applies at mount
 * time: same scripts, same per-app history table, so CI/CD can migrate ahead of a rolling or canary
 * rollout (expand/contract, design ch. 31). {@code info}, {@code validate} and {@code repair} cover
 * the read-only and recovery operations without serving the app, mirroring {@code tesseraql migrate}
 * so the Maven and CLI surfaces stay one engine.
 */
@Mojo(name = "migrate", threadSafe = true)
public class MigrateMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    /** The app name keying the history table; matches the runtime's mounted app name. */
    @Parameter(property = "tesseraql.appName", defaultValue = "${project.artifactId}")
    private String appName;

    /**
     * Which migration set to act on: {@code main} uses {@code db/migration}, any other name uses
     * {@code db/<datasource>/migration} (the jdbcUrl must point at that datasource's database).
     */
    @Parameter(property = "tesseraql.datasource", defaultValue = "main")
    private String datasource;

    /** The operation to run: {@code apply} (default), {@code info}, {@code validate}, {@code repair}. */
    @Parameter(property = "tesseraql.migrate.operation", defaultValue = "apply")
    private String operation;

    @Parameter(property = "tesseraql.jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "tesseraql.username")
    private String username;

    @Parameter(property = "tesseraql.password")
    private String password;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username,
                password);
        java.nio.file.Path home = appHome.toPath();
        switch (operation.toLowerCase(Locale.ROOT)) {
            case "apply" -> AppMigrator.migrate(home, appName, datasource, dataSource)
                    .ifPresentOrElse(
                            result -> getLog().info("Applied " + result.applied()
                                    + " migration(s) for app " + appName + ", datasource "
                                    + datasource + " (history table " + result.historyTable()
                                    + ")"),
                            this::logNoDirectory);
            case "info" -> AppMigrator.info(home, appName, datasource, dataSource).ifPresentOrElse(
                    info -> {
                        info.migrations().forEach(migration -> getLog().info(String.format(
                                "%-12s %-10s %s", migration.version(), migration.state(),
                                migration.description())));
                        getLog().info("TesseraQL migrate info: " + info.migrations().size()
                                + " migration(s) in " + info.historyTable());
                    },
                    this::logNoDirectory);
            case "validate" -> {
                AppMigrator.ValidateResult result = AppMigrator
                        .validate(home, appName, datasource, dataSource).orElse(null);
                if (result == null) {
                    logNoDirectory();
                } else if (result.valid()) {
                    getLog().info("TesseraQL migrate validate: valid (" + result.historyTable()
                            + ")");
                } else {
                    result.problems().forEach(getLog()::error);
                    throw new MojoFailureException("TesseraQL migrate validate failed: "
                            + result.problems().size() + " problem(s)");
                }
            }
            case "repair" -> AppMigrator.repair(home, appName, datasource, dataSource)
                    .ifPresentOrElse(
                            summary -> getLog().info("TesseraQL migrate repair: removed "
                                    + summary.removed() + ", deleted " + summary.deleted()
                                    + ", aligned " + summary.aligned() + " ("
                                    + summary.historyTable() + ")"),
                            this::logNoDirectory);
            default -> throw new MojoExecutionException("Unknown tesseraql.migrate.operation '"
                    + operation + "'; expected one of apply, info, validate, repair");
        }
    }

    private void logNoDirectory() {
        getLog().info("No migration directory for datasource '" + datasource + "' in " + appHome
                + "; nothing to do");
    }
}
