package io.tesseraql.maven;

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

    @Parameter(property = "tesseraql.jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "tesseraql.username")
    private String username;

    @Parameter(property = "tesseraql.password")
    private String password;

    @Override
    public void execute() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        AppMigrator.migrate(appHome.toPath(), appName, dataSource).ifPresentOrElse(
                result -> getLog().info("Applied " + result.applied() + " migration(s) for app "
                        + appName + " (history table " + result.historyTable() + ")"),
                () -> getLog().info("No db/migration directory in " + appHome + "; nothing to do"));
    }
}
