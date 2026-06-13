package io.tesseraql.maven;

import io.tesseraql.report.DriverManagerDataSource;
import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Applies the app's {@code db/migration} Flyway scripts to a database (design ch. 18). This is the
 * pre-deploy form of the migrations the runtime applies at mount time: same scripts, same per-app
 * history table, so CI/CD can migrate ahead of a rolling or canary rollout (expand/contract,
 * design ch. 31).
 */
@Mojo(name = "migrate", threadSafe = true)
public class MigrateMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    /** The app name keying the history table; matches the runtime's mounted app name. */
    @Parameter(property = "tesseraql.appName", defaultValue = "${project.artifactId}")
    private String appName;

    /**
     * Which migration set to apply: {@code main} runs {@code db/migration}, any other name runs
     * {@code db/<datasource>/migration} (the jdbcUrl must point at that datasource's database).
     */
    @Parameter(property = "tesseraql.datasource", defaultValue = "main")
    private String datasource;

    @Parameter(property = "tesseraql.jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "tesseraql.username")
    private String username;

    @Parameter(property = "tesseraql.password")
    private String password;

    @Override
    public void execute() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username,
                password);
        AppMigrator.migrate(appHome.toPath(), appName, datasource, dataSource).ifPresentOrElse(
                result -> getLog().info("Applied " + result.applied() + " migration(s) for app "
                        + appName + ", datasource " + datasource
                        + " (history table " + result.historyTable() + ")"),
                () -> getLog().info("No migration directory for datasource '" + datasource
                        + "' in " + appHome + "; nothing to do"));
    }
}
