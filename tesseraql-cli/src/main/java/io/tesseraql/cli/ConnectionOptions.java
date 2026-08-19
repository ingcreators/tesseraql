package io.tesseraql.cli;

import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;

/**
 * The connection option set (docs/cli-surface.md Decision 5): {@code --jdbc-url}, {@code
 * --username}, {@code --password} and {@code --datasource}, mixed into every command that opens a
 * database. It resolves a {@link DriverManagerDataSource}, falling back to the named datasource's
 * {@code tesseraql.datasources.<name>.*} config (default {@code main}) when no URL is given, kept
 * in one place so the CLI surfaces never drift. The {@code --datasource} name also keys
 * datasource-scoped work — {@code migrate}'s migration set, {@code schema}'s catalog — so one
 * flag never means two things. (The {@code mcp} dev-tools still carry their own copy of the
 * config fallback — argument-driven, not option-driven.)
 */
final class ConnectionOptions {

    @Option(names = {
            "--jdbc-url"}, description = "JDBC URL (default: the app's --datasource config).")
    String jdbcUrl;

    @Option(names = {"--datasource"}, paramLabel = "<name>", description = "Named datasource"
            + " whose configuration backs the connection, and the key for datasource-scoped work"
            + " (default: main).")
    String name = "main";

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
            url = config.getString(key("jdbcUrl")).orElseThrow(
                    () -> new IllegalArgumentException("No --jdbc-url given and the app config"
                            + " declares no " + key("jdbcUrl")));
            if (user == null) {
                user = config.getString(key("username")).orElse(null);
            }
            if (pass == null) {
                pass = config.getString(key("password")).orElse(null);
            }
        }
        return new DriverManagerDataSource(url, user, pass);
    }

    /**
     * Resolves like {@link #resolve(AppConfig)}, plus the {@code dev --embedded-db} first-login
     * hand-off: when no explicit {@code --jdbc-url} is given and {@code appHome} carries the
     * running-embedded-database marker ({@value EmbeddedDbMarker#RELATIVE_PATH}), the marker's URL
     * backs the command whenever the config's main datasource is missing, unresolvable, or does
     * not answer a connection while the marker's does (announced with one line on stdout).
     * Precedence: explicit {@code --jdbc-url}, then a resolvable-and-reachable config, then the
     * embedded marker. Without a marker file nothing is probed, so apps with a real configured
     * database resolve exactly as before.
     */
    DriverManagerDataSource resolve(AppConfig config, Path appHome) {
        if (jdbcUrl == null && appHome != null) {
            String configUrl = null;
            String configUser = username;
            String configPass = password;
            if (config != null) {
                try {
                    configUrl = config.getString(key("jdbcUrl")).orElse(null);
                    if (configUser == null) {
                        configUser = config.getString(key("username")).orElse(null);
                    }
                    if (configPass == null) {
                        configPass = config.getString(key("password")).orElse(null);
                    }
                } catch (RuntimeException ex) {
                    // Unresolvable placeholders (e.g. ${db.main.url} with no input declared) —
                    // exactly the situation the running embedded database can answer for.
                    configUrl = null;
                }
            }
            Optional<String> marker = EmbeddedDbMarker.pick(appHome, configUrl, configUser,
                    configPass, EmbeddedDbMarker::reachable);
            if (marker.isPresent()) {
                System.out.println("Using the running embedded database ("
                        + EmbeddedDbMarker.RELATIVE_PATH + ")");
                return new DriverManagerDataSource(marker.get(), null, null);
            }
        }
        return resolve(config);
    }

    /** The configuration key for {@code field} under the named datasource. */
    private String key(String field) {
        return "tesseraql.datasources." + name + "." + field;
    }
}
