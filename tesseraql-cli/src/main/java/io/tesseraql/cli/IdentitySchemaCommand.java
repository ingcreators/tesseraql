package io.tesseraql.cli;

import io.tesseraql.apptasks.IdentityBootstrap;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql identity-schema}: applies the managed realm's standard IAM schema
 * ({@code tql_*} tables, design ch. 10.3) and optionally seeds a bootstrap administrator — the
 * CLI-native form of the {@code tesseraql:identity-schema} goal, over the shared
 * {@link IdentityBootstrap}. The admin password comes from {@code --admin-password-file} or the
 * {@code TESSERAQL_ADMIN_PASSWORD} environment variable — never a command-line argument, so it
 * cannot leak into shell history or process listings.
 */
@Command(name = "identity-schema", description = "Apply the managed IAM schema and optionally seed an administrator.")
final class IdentitySchemaCommand implements Callable<Integer> {

    @Option(names = {
            "--app"}, description = "App home for datasource fallback (optional; else use --jdbc-url).")
    Path app;

    @Mixin
    CliDatasource datasource;

    @Option(names = {"--dialect"}, description = "SQL dialect (default: postgres).")
    String dialect = "postgres";

    @Option(names = {
            "--admin-login"}, description = "Administrator to create or update after the schema is applied.")
    String adminLogin;

    @Option(names = {
            "--admin-password-file"}, description = "File holding the admin password (else TESSERAQL_ADMIN_PASSWORD).")
    Path adminPasswordFile;

    @Option(names = {
            "--admin-roles"}, description = "Role codes assigned to the administrator (default: iam.admin).")
    String adminRoles = "iam.admin";

    @Option(names = {
            "--admin-permissions"}, description = "Permission codes created and granted to the admin roles.")
    String adminPermissions = "";

    @Override
    public Integer call() throws Exception {
        AppConfig config = app != null ? new ManifestLoader().load(app).config() : null;
        DriverManagerDataSource dataSource = datasource.resolve(config);
        IdentityBootstrap bootstrap = new IdentityBootstrap(dataSource);
        bootstrap.applySchema(dialect);
        System.out.println("Applied the managed IAM schema (" + dialect + ")");
        if (adminLogin != null && !adminLogin.isBlank()) {
            List<String> roles = csv(adminRoles);
            List<String> permissions = csv(adminPermissions);
            bootstrap.seedAdmin(adminLogin, adminPassword(), roles, permissions);
            System.out.println("Seeded administrator '" + adminLogin + "' with roles " + roles
                    + (permissions.isEmpty() ? "" : " and permissions " + permissions));
        }
        return 0;
    }

    private static List<String> csv(String values) {
        return values == null
                ? List.of()
                : Arrays.stream(values.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private String adminPassword() throws Exception {
        if (adminPasswordFile != null) {
            return Files.readString(adminPasswordFile).trim();
        }
        String env = System.getenv("TESSERAQL_ADMIN_PASSWORD");
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException("--admin-login is set but no password was provided:"
                    + " set --admin-password-file or TESSERAQL_ADMIN_PASSWORD");
        }
        return env;
    }
}
