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

    @Option(names = {"--suite"}, paramLabel = "<dir>", description = "Directory holding the"
            + " applications to serve: an install root (catalog.json) or a folder of application"
            + " homes. Exactly one of --suite or --app.")
    Path suite;

    @Option(names = {"--app"}, paramLabel = "<dir>", description = "One application home to serve."
            + " Exactly one of --suite or --app.")
    Path app;

    @Option(names = {
            "--port"}, description = "The port the gateway fronts every app on (default 8080).")
    int port = 8080;

    @Option(names = {
            "--mode"}, paramLabel = "<suite|isolated>", description = "suite: one origin, /apps/<id>/ per app, one session across them."
                    + " isolated: a hostname per app, sessions not shared. Default suite.")
    String mode = "suite";

    @Option(names = {
            "--http2"}, description = "Serve and forward cleartext HTTP/2 (h2c). Off by default."
                    + " One switch moves both hops: a client's connection to the gateway and the"
                    + " gateway's connection to each app. An app that does not offer h2c answers"
                    + " the upgrade over HTTP/1.1 and is reached exactly as before.")
    boolean http2;

    @Option(names = {
            "--trusted-proxies"}, paramLabel = "<cidr,...>", description = "Addresses whose forwarded headers come from your edge rather than"
                    + " from a caller, e.g. 10.0.0.0/8,192.168.1.5. When set, an application's"
                    + " mTLS forwardedHeader is stripped from requests arriving from anywhere"
                    + " else. Empty by default, which strips nothing: the edge overwriting the"
                    + " header on every inbound request is the contract either way.")
    String trustedProxies;

    @Override
    public Integer call() throws Exception {
        if ((suite == null) == (app == null)) {
            System.err.println("Choose one: --suite <dir> serves every application the directory"
                    + " holds, --app <dir> serves one.");
            return 2;
        }
        // Resolved here rather than inside the gateway, because only the caller knows which flag
        // was typed and therefore which refusal is the useful one
        // (docs/cli-surface.md Decision 3).
        Path served;
        try {
            served = suite != null
                    ? suite
                    : io.tesseraql.operations.app.AppDirectory.application(app, "tesseraql host");
            if (suite != null) {
                io.tesseraql.operations.app.AppDirectory.suite(suite);
            }
        } catch (io.tesseraql.core.error.TqlException refused) {
            System.err.println(refused.getMessage());
            return 2;
        }

        MultiAppGateway.Mode selected;
        try {
            selected = MultiAppGateway.Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            System.err.println("Unknown --mode '" + mode + "'; expected suite or isolated.");
            return 2;
        }

        try (MultiAppGateway gateway = MultiAppGateway.start(served, port,
                new MultiAppGateway.Settings(selected, http2, trustedProxies))) {
            System.out.println("TesseraQL hosting " + gateway.appIds().size()
                    + " app(s) on port " + gateway.port() + " (" + mode.toLowerCase(
                            java.util.Locale.ROOT)
                    + " mode" + (http2 ? ", h2c" : "") + ")");
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
