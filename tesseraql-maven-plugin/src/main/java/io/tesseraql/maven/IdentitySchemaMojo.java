package io.tesseraql.maven;

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

    /** Role codes assigned to the administrator. */
    @Parameter(property = "tesseraql.adminRoles", defaultValue = "iam.admin")
    private String adminRoles;

    @Override
    public void execute() throws MojoExecutionException {
        IdentityBootstrap bootstrap = new IdentityBootstrap(
                new DriverManagerDataSource(jdbcUrl, username, password));
        try {
            bootstrap.applySchema(dialect);
            getLog().info("Applied the managed IAM schema (" + dialect + ")");
            if (adminLogin != null && !adminLogin.isBlank()) {
                List<String> roles = Arrays.stream(adminRoles.split(","))
                        .map(String::trim).filter(role -> !role.isEmpty()).toList();
                bootstrap.seedAdmin(adminLogin, adminPassword(), roles);
                getLog().info("Seeded administrator '" + adminLogin + "' with roles " + roles);
            }
        } catch (SQLException ex) {
            throw new MojoExecutionException("Identity schema bootstrap failed: "
                    + ex.getMessage(), ex);
        }
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
