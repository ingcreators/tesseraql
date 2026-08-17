package io.tesseraql.cli;

import io.tesseraql.runtime.MultiAppGateway;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql host --stack <dir>}: serves the applications a directory holds, each in its own
 * runtime, behind one port (docs/cli-surface.md decision 1).
 *
 * <p>The stack directory is required rather than discovered, because production does not guess
 * (docs/cli-surface.md decision 9). {@code --app-name} narrows to one member of the stack without
 * changing its address — narrowing is a filter, never a second deployment shape, which is why
 * there is no {@code --app} here: serving an application "on its own" gave it a second address,
 * and one application has one address (docs/stack-architecture.md decision 12).
 *
 * <p>Each app keeps its own Camel context, datasource set, Studio, and traces; what they share is
 * the process and whatever database their configurations happen to name — the framework enables
 * that separation and never guarantees it, because an application's configuration is not the
 * authority on its connection (docs/app-isolation-model.md decision 3).
 */
@Command(name = "host", description = "Serve every installed app from one port, each in its own runtime.")
final class HostCommand implements Callable<Integer> {

    @Option(names = {"--stack"}, required = true, paramLabel = "<dir>", description = "Directory"
            + " holding the applications to serve: an install root (catalog.json) or a folder of"
            + " application homes.")
    Path stack;

    @Option(names = {"--app-name"}, paramLabel = "<name>", description = "Serve only this"
            + " application from the stack, at the same address it has as a stack member.")
    String appName;

    @Option(names = {
            "--port"}, description = "The port the gateway fronts every app on (default 8080).")
    int port = 8080;

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
        // Resolved here rather than inside the gateway, because only the caller knows which flag
        // was typed and therefore which refusal is the useful one
        // (docs/cli-surface.md Decision 3).
        try {
            io.tesseraql.operations.app.AppDirectory.stack(stack);
        } catch (io.tesseraql.core.error.TqlException refused) {
            System.err.println(refused.getMessage());
            return 2;
        }

        try (MultiAppGateway gateway = MultiAppGateway.start(stack, port,
                new MultiAppGateway.Settings(http2, trustedProxies), appName)) {
            System.out.println("TesseraQL hosting " + gateway.appIds().size()
                    + " app(s) on port " + gateway.port() + (http2 ? " (h2c)" : ""));
            for (String appId : gateway.appIds()) {
                System.out.println("  " + appId);
            }
            // The gateway serves on its own threads; hold the command open until interrupted.
            Thread.currentThread().join();
        } catch (io.tesseraql.core.error.TqlException refused) {
            System.err.println(refused.getMessage());
            return 2;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }
}
