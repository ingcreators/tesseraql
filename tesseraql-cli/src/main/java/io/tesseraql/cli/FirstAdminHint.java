package io.tesseraql.cli;

import io.tesseraql.runtime.DataSources;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * The first-run hand-off hint for {@code serve}: every entry path stalls at the login form while
 * the identity store is unseeded, so when the managed schema holds no users (or was never applied)
 * the CLI says how to create the first administrator right after "TesseraQL serving on port ...".
 * The check runs once at startup, never per request, and is best-effort throughout — any error
 * (no database, no permission, placeholders that do not resolve) suppresses the hint rather than
 * failing or delaying startup. Under {@code --embedded-db} the suggested command works as printed
 * because {@code identity-schema --app} picks up the {@link EmbeddedDbMarker}.
 */
final class FirstAdminHint {

    private FirstAdminHint() {
    }

    /**
     * The hint to print, or empty when users already exist, the password login form is switched
     * off ({@code tesseraql.console.login.password.enabled} — the same gate the bundled login page
     * uses, so the hint only shows when the managed realm is a login path), or the main datasource
     * is not reachable. {@code override} is the embedded database when {@code serve} runs with
     * {@code --embedded-db}; otherwise the app config's main datasource is probed.
     */
    static Optional<String> check(AppConfig config, Path app,
            DataSources.MainDatasourceOverride override) {
        try {
            boolean passwordLogin = config.getString("tesseraql.console.login.password.enabled")
                    .map(Boolean::parseBoolean).orElse(true);
            if (!passwordLogin) {
                return Optional.empty();
            }
            String url = override != null
                    ? override.jdbcUrl()
                    : config.getString("tesseraql.datasources.main.jdbcUrl").orElse(null);
            if (url == null) {
                return Optional.empty();
            }
            String user = override != null
                    ? override.username()
                    : config.getString("tesseraql.datasources.main.username").orElse(null);
            String password = override != null
                    ? override.password()
                    : config.getString("tesseraql.datasources.main.password").orElse(null);
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                if (hasUsers(connection)) {
                    return Optional.empty();
                }
            }
            return Optional.of(hint(app));
        } catch (Exception ex) {
            // A convenience hint must never fail (or add noise to) startup.
            return Optional.empty();
        }
    }

    /** True when {@code tql_users} exists and holds at least one row; a missing table counts as no users. */
    private static boolean hasUsers(Connection connection) {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from tql_users")) {
            return rows.next() && rows.getLong(1) > 0;
        } catch (SQLException ex) {
            return false;
        }
    }

    private static String hint(Path app) {
        return "No users exist yet - create the first administrator:\n"
                + "  tesseraql identity-schema --app " + app
                + " --admin-login admin --admin-password-file <file>";
    }
}
