package io.tesseraql.cli;

import io.tesseraql.apptasks.AppPackager;
import io.tesseraql.core.util.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql package --app <dir>}: packages the app home into a deterministic {@code .tqlapp}
 * archive with a sibling {@code .sha256} (design ch. 32.3) — the CLI-native form of the
 * {@code package-app} goal, over the shared {@link AppPackager}. Build-generated docs (from
 * {@code tesseraql generate}) are merged in under the reserved namespace when present.
 */
@Command(name = "package", description = "Package the app home into a deterministic .tqlapp.")
final class PackageCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Option(names = {
            "--out"}, description = "Output archive (default: <app>/work/<app-name>.tqlapp).")
    Path out;

    @Option(names = {"--generated"}, description = "Generated docs directory to merge"
            + " (default: <app>/work/generated/docs when present).")
    Path generated;

    @Override
    public Integer call() throws Exception {
        Path home = app.toAbsolutePath().normalize();
        Path output = out != null
                ? out
                : home.resolve("work").resolve(home.getFileName() + ".tqlapp");
        Path docs = generated != null ? generated : home.resolve("work/generated/docs");
        Path generatedDocs = Files.isDirectory(docs) ? docs : null;
        new AppPackager().pack(home, generatedDocs, output);
        // The sibling checksum lets installs verify package integrity (design ch. 49, 50).
        String sha256 = Hashing.sha256(output);
        Files.writeString(output.resolveSibling(output.getFileName() + ".sha256"), sha256 + "\n");
        System.out.println("Packaged TesseraQL app to " + output + " (sha256 " + sha256 + ")");
        return 0;
    }
}
