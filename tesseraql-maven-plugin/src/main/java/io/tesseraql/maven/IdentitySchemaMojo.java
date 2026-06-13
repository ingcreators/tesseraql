package io.tesseraql.maven;

import io.tesseraql.report.DriverManagerDataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Applies the managed realm's standard IAM schema ({@code tql_*} tables, design ch. 10.3) to a
 * database and optionally seeds a bootstrap administrator (design ch. 18 identity goals). The
 * admin password comes from a file ({@code tesseraql.adminPasswordFile}) or the
 * {@code TESSERAQL_ADMIN_PASSWORD} environment variable - never from the POM or the command line,
 * so it cannot leak into build logs or version control.
 *
 * <p>There are no default credentials anywhere: a fresh database has no users until this goal
 * (or equivalent provisioning) runs. The role codes in {@code tesseraql.adminRoles} are only
 * meaningful if the app's {@code tesseraql.security.policies} reference the same names - check
 * the app's policy block and pass matching roles (e.g. {@code -Dtesseraql.adminRoles=ADMIN}).
 * Permission codes in {@code tesseraql.adminPermissions} (e.g. {@code ops.app.*} for runtime-wide
 * operations visibility) are created and granted to those roles, flowing into the principal's
 * permissions through the standard role-permission join.
 */
@Mojo(name = "identity-schema", threadSafe = true)
public class IdentitySchemaMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "tesseraql.username")
    private String username;

    @Parameter(property = "tesseraql.password")
    private String password;

    @Parameter(property = "tesseraql.dialect", defaultValue = "postgres")
    private String dialect;

    /** When set, the administrator to create or update after the schema is applied. */
    @Parameter(property = "tesseraql.adminLogin")
    private String adminLogin;

    /** A file holding the admin password (e.g. a CI secret mount). */
    @Parameter(property = "tesseraql.adminPasswordFile")
    private File adminPasswordFile;

    /**
     * Role codes assigned to the administrator. Must match the role names the app's
     * {@code tesseraql.security.policies} block checks, or the admin can log in but passes no
     * policy gate.
     */
    @Parameter(property = "tesseraql.adminRoles", defaultValue = "iam.admin")
    private String adminRoles;

    /**
     * Permission codes created and granted to the admin roles, e.g.
     * {@code ops.app.*,iam:admin:write}. Empty by default.
     */
    @Parameter(property = "tesseraql.adminPermissions", defaultValue = "")
    private String adminPermissions;

    @Override
    public void execute() throws MojoExecutionException {
        IdentityBootstrap bootstrap = new IdentityBootstrap(
                new DriverManagerDataSource(jdbcUrl, username, password));
        try {
            bootstrap.applySchema(dialect);
            getLog().info("Applied the managed IAM schema (" + dialect + ")");
            if (adminLogin != null && !adminLogin.isBlank()) {
                List<String> roles = csv(adminRoles);
                List<String> permissions = csv(adminPermissions);
                bootstrap.seedAdmin(adminLogin, adminPassword(), roles, permissions);
                getLog().info("Seeded administrator '" + adminLogin + "' with roles " + roles
                        + (permissions.isEmpty() ? "" : " and permissions " + permissions));
            }
        } catch (SQLException ex) {
            throw new MojoExecutionException("Identity schema bootstrap failed: "
                    + ex.getMessage(), ex);
        }
    }

    private static List<String> csv(String values) {
        return values == null
                ? List.of()
                : Arrays.stream(values.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private String adminPassword() throws MojoExecutionException {
        if (adminPasswordFile != null) {
            try {
                return Files.readString(adminPasswordFile.toPath()).trim();
            } catch (IOException ex) {
                throw new MojoExecutionException("Cannot read tesseraql.adminPasswordFile: "
                        + ex.getMessage(), ex);
            }
        }
        String env = System.getenv("TESSERAQL_ADMIN_PASSWORD");
        if (env == null || env.isBlank()) {
            throw new MojoExecutionException("tesseraql.adminLogin is set but no password was"
                    + " provided: set tesseraql.adminPasswordFile or TESSERAQL_ADMIN_PASSWORD");
        }
        return env;
    }
}
