package io.tesseraql.cli;

import io.tesseraql.runtime.MultiAppGateway;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql host --install-root <dir>}: serves every application installed under an install
 * root, each in its own runtime, behind one port (docs/app-isolation-model.md).
 *
 * <p>This is the counterpart of {@code serve}, which runs one application. Each app here keeps its
 * own Camel context, datasource set, Studio, and traces; what they share is the process and
 * whatever database their configurations happen to name — the framework enables that separation
 * and never guarantees it, because an application's configuration is not the authority on its
 * connection (decision 3).
 */
@Command(name = "host", description = "Serve every installed app from one port, each in its own runtime.")
final class HostCommand implements Callable<Integer> {

    @Option(names = {
            "--install-root"}, required = true, description = "Directory holding catalog.json and the installed app trees.")
    Path installRoot;

    @Option(names = {
            "--port"}, description = "The port the gateway fronts every app on (default 8080).")
    int port = 8080;

    @Option(names = {
            "--mode"}, paramLabel = "<suite|isolated>", description = "suite: one origin, /apps/<id>/ per app, one session across them."
                    + " isolated: a hostname per app, sessions not shared. Default suite.")
    String mode = "suite";

    @Override
    public Integer call() throws Exception {
        MultiAppGateway.Mode selected;
        try {
            selected = MultiAppGateway.Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            System.err.println("Unknown --mode '" + mode + "'; expected suite or isolated.");
            return 2;
        }

        try (MultiAppGateway gateway = MultiAppGateway.start(installRoot, port, selected)) {
            System.out.println("TesseraQL hosting " + gateway.appIds().size()
                    + " app(s) on port " + gateway.port() + " (" + mode.toLowerCase(
                            java.util.Locale.ROOT)
                    + " mode)");
            for (String appId : gateway.appIds()) {
                System.out.println("  " + appId);
            }
            // The gateway serves on its own threads; hold the command open until interrupted.
            Thread.currentThread().join();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }
}
