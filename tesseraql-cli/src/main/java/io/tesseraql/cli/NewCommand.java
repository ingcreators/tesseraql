package io.tesseraql.cli;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql new <app>}: generates a runnable skeleton — config, a Phase 18-conventions
 * migration, a home page, a starter search route, and a smoke suite (roadmap Phase 23).
 */
@Command(name = "new", description = "Generate a runnable app skeleton.")
final class NewCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The app name ([a-z][a-z0-9-]*); also the directory.")
    String appName;

    @Option(names = {"--dir"}, description = "Parent directory to create the app in (default: .).")
    Path dir = Path.of(".");

    @Override
    public Integer call() {
        AppScaffolder scaffolder = new AppScaffolder();
        List<ScaffoldedFile> files = scaffolder.scaffold(appName);
        Path home = dir.resolve(appName);
        scaffolder.writeNew(home, files);

        System.out.println("Created app '" + appName + "' at "
                + home.toAbsolutePath().normalize() + " (" + files.size() + " files).");
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  cd " + appName);
        System.out.println("  # point config/application.yml at your database, then");
        System.out.println("  tesseraql serve --app .");
        System.out.println("  tesseraql scaffold crud --app . --table items");
        return 0;
    }
}
