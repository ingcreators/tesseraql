package io.tesseraql.cli;

import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.yaml.config.AppConfig;
import picocli.CommandLine.Option;

/**
 * Shared datasource options for the database-touching subcommands. Mixed into a command with
 * {@code @Mixin}, it exposes {@code --jdbc-url/--username/--password} and resolves a
 * {@link DriverManagerDataSource}, falling back to the app's {@code tesseraql.datasources.main.*}
 * config when no URL is given — the same resolution the {@code mcp} dev-tools and
 * {@code scaffold crud} use, kept in one place so the CLI surfaces never drift.
 */
final class CliDatasource {

    @Option(names = {"--jdbc-url"}, description = "JDBC URL (default: the app's main datasource).")
    String jdbcUrl;

    @Option(names = {"--username"}, description = "Database user for --jdbc-url.")
    String username;

    @Option(names = {"--password"}, description = "Database password for --jdbc-url.")
    String password;

    /**
     * Resolves the datasource: the explicit {@code --jdbc-url} (with its credentials), or the app's
     * main datasource from {@code config} when no URL is given. {@code config} may be {@code null}
     * (commands without an app home), in which case {@code --jdbc-url} is required.
     */
    DriverManagerDataSource resolve(AppConfig config) {
        String url = jdbcUrl;
        String user = username;
        String pass = password;
        if (url == null) {
            if (config == null) {
                throw new IllegalArgumentException("--jdbc-url is required");
            }
            url = config.getString("tesseraql.datasources.main.jdbcUrl").orElseThrow(
                    () -> new IllegalArgumentException("No --jdbc-url given and the app config"
                            + " declares no tesseraql.datasources.main.jdbcUrl"));
            if (user == null) {
                user = config.getString("tesseraql.datasources.main.username").orElse(null);
            }
            if (pass == null) {
                pass = config.getString("tesseraql.datasources.main.password").orElse(null);
            }
        }
        return new DriverManagerDataSource(url, user, pass);
    }
}
