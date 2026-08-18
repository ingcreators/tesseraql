package io.tesseraql.cli;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql new <app>}: generates a runnable skeleton — config, a Phase 18-conventions
 * migration, a home page, a starter search route, and a smoke suite (roadmap Phase 23).
 *
 * <p>The flag is {@code --stack}, because the directory a new application is created inside is by
 * definition a directory holding applications (docs/cli-surface.md Decision 8) — the same word
 * carries the same directory from creation to running. And {@code new} writes the stack's marker,
 * {@code tesseraql-stack.yml}, beside the application: discovery's one-level step up keys on it
 * (Decision 9), so a layout created here carries its marker from birth. All guidance comments —
 * the file has no required content, and a marker needs none to mark.
 */
@Command(name = "new", description = "Generate a runnable app skeleton.")
final class NewCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The app name ([a-z][a-z0-9-]*); also the directory.")
    String appName;

    @Option(names = {"--stack"}, description = "The stack directory to create the app in"
            + " (default: .) — the directory that holds your applications.")
    Path stack = Path.of(".");

    /** What {@code new} writes as the stack's marker: guidance, and room for the settings. */
    static final String STACK_FILE_TEMPLATE = """
            # tesseraql-stack.yml — this directory is a TesseraQL stack: it holds applications,
            # and settings here apply to all of them. `tesseraql dev` finds the stack by this
            # file. Everything below is optional; placeholders like ${ENV_VAR:default} and
            # ${secret.env.NAME} resolve exactly as they do in an application's config.
            #
            # framework:
            #   datasource:              # one sign-in across the stack rides this connection
            #     jdbcUrl: jdbc:postgresql://${DB_HOST:localhost}:5432/stack
            #     username: ${secret.env.STACK_DB_USER}
            #     password: ${secret.env.STACK_DB_PASSWORD}
            #
            # externalOrigin: https://apps.example.com   # required only when MCP or the token
            #                                            # issuer reads it; dev defaults it
            #
            # root:
            #   redirect: orders           # /  ->  /orders; omitted, / lands on the portal
            #
            # security:
            #   jwt:
            #     algorithm: RS256
            #     issuer: https://apps.example.com/_tesseraql/oauth
            #     jwksUri: https://apps.example.com/_tesseraql/oauth/jwks
            """;

    @Override
    public Integer call() {
        AppScaffolder scaffolder = new AppScaffolder();
        List<ScaffoldedFile> files = scaffolder.scaffold(appName);
        Path home = stack.resolve(appName);
        scaffolder.writeNew(home, files);
        boolean marked = writeStackMarker();

        System.out.println("Created app '" + appName + "' at "
                + home.toAbsolutePath().normalize() + " (" + files.size() + " files"
                + (marked ? ", + " + io.tesseraql.operations.app.StackSettings.FILE_NAME : "")
                + ").");
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  cd " + appName);
        System.out.println("  # point config/application.yml at your database, then");
        System.out.println("  tesseraql dev");
        System.out.println("  # (or skip the database: tesseraql dev --embedded-db)");
        System.out.println("  tesseraql scaffold crud --app . --table items");
        return 0;
    }

    /**
     * Writes the marker beside the application, never over an existing one — a stack that
     * already declares settings keeps them, and creating a second application in it changes
     * nothing. A fresh stack also gets a {@code .gitignore} for the stack-level {@code work/}
     * directory the host materializes the portal surface into (docs/root-portal.md), under the
     * same never-overwrite rule: a stack that already manages its ignores keeps them.
     */
    private boolean writeStackMarker() {
        Path marker = stack.resolve(io.tesseraql.operations.app.StackSettings.FILE_NAME);
        if (Files.exists(marker)) {
            return false;
        }
        try {
            Files.writeString(marker, STACK_FILE_TEMPLATE);
            Path gitignore = stack.resolve(".gitignore");
            if (!Files.exists(gitignore)) {
                Files.writeString(gitignore, """
                        # Stack-level runtime scratch (the hosted portal surface); never committed.
                        work/
                        """);
            }
            return true;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
