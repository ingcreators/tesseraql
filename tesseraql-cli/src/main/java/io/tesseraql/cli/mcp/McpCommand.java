package io.tesseraql.cli.mcp;

import io.tesseraql.mcp.HttpTransport;
import io.tesseraql.mcp.McpAuthenticator;
import io.tesseraql.mcp.McpHttpHandler;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.mcp.StdioTransport;
import io.tesseraql.operations.app.AppDirectory;
import io.tesseraql.operations.app.InstalledApp;
import io.tesseraql.runtime.SecurityConfigFactory;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql mcp}: serves the developer MCP tools (roadmap Phase 24) so a coding agent can
 * scaffold, lint, test, inspect, and draft-edit applications over the Model Context Protocol.
 *
 * <p>One server spans the stack (docs/stack-architecture.md decision 19): {@code --stack} names
 * the directory holding the applications, discovered one level up from the working directory when
 * omitted (docs/cli-surface.md decision 9), and {@code --app-name} narrows to one application
 * without changing anything else about how it is addressed or configured. Every tool carries an
 * {@code application} argument naming which stack member it operates on.
 *
 * <p>Two transports: {@code stdio} (default - an agent launches this as a subprocess) and
 * {@code http} (a Streamable HTTP endpoint at {@code /mcp} for a shared development server). The
 * HTTP transport reuses the framework's bearer-token check ({@code tesseraql.security.jwt}); it
 * refuses to expose tools off-loopback without authentication unless {@code --insecure} is given.
 * {@code --read-only} drops the write tools (scaffold, drafts) for safe shared exposure - a
 * property of the server, never of one application.
 */
@Command(name = "mcp", description = "Serve the developer MCP tools over stdio or HTTP.")
public final class McpCommand implements Callable<Integer> {

    /** The supported MCP transports. */
    public enum Transport {
        stdio, http
    }

    @Option(names = {"--stack"}, paramLabel = "<dir>", description = "Directory holding the"
            + " applications to serve: an install root (catalog.json) or a folder of application"
            + " homes. Discovered one level up from the working directory when omitted.")
    Path stack;

    @Option(names = {"--app-name"}, paramLabel = "<name>", description = "Serve only this"
            + " application's tools from the stack.")
    String appName;

    @Option(names = {
            "--transport"}, defaultValue = "stdio", description = "Transport: stdio (default) or http.")
    Transport transport;

    @Option(names = {"--read-only"}, description = "Expose only the read tools.")
    boolean readOnly;

    @Option(names = {"--port"}, defaultValue = "8765", description = "HTTP port (http transport).")
    int port;

    @Option(names = {
            "--bind"}, defaultValue = "127.0.0.1", description = "HTTP bind address (http transport).")
    String bind;

    @Option(names = {"--insecure"}, description = "Allow the HTTP transport off-loopback without"
            + " authentication.")
    boolean insecure;

    @Override
    public Integer call() throws Exception {
        // The stack: named, or discovered one level up from the working directory
        // (docs/cli-surface.md decision 9) - the same resolution dev runs, because the agent's
        // view of what exists should match what dev serves.
        Map<String, Path> applications;
        try {
            applications = resolveApplications();
        } catch (io.tesseraql.core.error.TqlException refused) {
            System.err.println(refused.getMessage());
            return 2;
        }
        if (applications == null) {
            return 2;
        }
        // The dev tools lint and test the applications, so custom expression functions install
        // from every member's declared tesseraql.modules (the same wiring dev boots with,
        // composed onto one classloader - interim until decision 28 wires modules per runtime).
        io.tesseraql.cli.CliModules.installAppExtensions(List.copyOf(applications.values()), null);
        McpServer server = new McpDevTools(applications, readOnly).toServer();
        return transport == Transport.http
                ? serveHttp(server, applications)
                : serveStdio(server);
    }

    /**
     * The stack's applications, name to home, narrowed when {@code --app-name} is given - or
     * {@code null} after printing the refusal when the narrowing names nothing.
     */
    private Map<String, Path> resolveApplications() {
        AppDirectory.Resolved resolved;
        if (stack != null) {
            AppDirectory.stack(stack);
            resolved = AppDirectory.resolve(stack);
        } else {
            resolved = AppDirectory.discover(Path.of(".").toAbsolutePath().normalize());
        }
        Map<String, Path> applications = new LinkedHashMap<>();
        for (InstalledApp app : AppDirectory.applications(resolved)) {
            applications.put(app.name(), resolved.root().resolve(app.path()).normalize());
        }
        if (appName != null) {
            if (!applications.containsKey(appName)) {
                System.err.println("The stack holds no application named '" + appName + "'."
                        + " It holds: " + String.join(", ", applications.keySet()));
                return null;
            }
            applications.keySet().retainAll(Set.of(appName));
        }
        return applications;
    }

    private Integer serveStdio(McpServer server) throws IOException {
        // stdout carries protocol frames only; route everything else (logging, stray prints) to
        // stderr by swapping System.out before serving, keeping the real stream for the transport.
        PrintStream protocolOut = System.out;
        System.setOut(System.err);
        new StdioTransport(server, System.in, protocolOut).serve();
        return 0;
    }

    private Integer serveHttp(McpServer server, Map<String, Path> applications) throws Exception {
        Set<SecurityConfig.JwtConfig> contracts = jwtContracts(applications);
        if (contracts.size() > 1) {
            // One sign-in spans a stack; a server that verified each request against whichever
            // member it happened to pick would accept a token another member rejects.
            System.err.println("The stack's applications disagree on tesseraql.security.jwt, so"
                    + " the MCP HTTP transport cannot reuse one bearer contract. Align the"
                    + " applications' JWT settings, narrow with --app-name, or use the stdio"
                    + " transport.");
            return 2;
        }
        SecurityConfig.JwtConfig jwt = contracts.isEmpty() ? null : contracts.iterator().next();
        McpAuthenticator authenticator = jwt == null
                ? null
                : new JwtMcpAuthenticator(new JwtAuthenticator(jwt));
        if (authenticator == null && !isLoopback(bind) && !insecure) {
            System.err.println("Refusing to serve MCP on " + bind + " without authentication."
                    + " Configure tesseraql.security.jwt.secret, bind to localhost, or pass"
                    + " --insecure.");
            return 2;
        }
        if (authenticator == null) {
            System.err.println("WARNING: the MCP HTTP server has no authentication"
                    + (isLoopback(bind) ? " (bound to " + bind + ")." : " (--insecure)."));
        }
        HttpTransport http = new HttpTransport(new McpHttpHandler(server, authenticator), bind,
                port, "/mcp");
        http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(http::stop));
        System.out.println("TesseraQL MCP serving at " + http.url()
                + (readOnly ? " (read-only)" : "") + ". Press Ctrl+C to stop.");
        Thread.currentThread().join();
        return 0;
    }

    /**
     * The distinct non-null bearer contracts the stack's members configure. Empty means no member
     * configures one; two or more means they disagree and the HTTP transport refuses rather than
     * picking a member whose contract the others reject.
     */
    private static Set<SecurityConfig.JwtConfig> jwtContracts(Map<String, Path> applications) {
        Set<SecurityConfig.JwtConfig> contracts = new LinkedHashSet<>();
        for (Path home : applications.values()) {
            SecurityConfig security = SecurityConfigFactory
                    .build(new ManifestLoader().load(home).config());
            if (security.jwt() != null) {
                contracts.add(security.jwt());
            }
        }
        return contracts;
    }

    private static boolean isLoopback(String bind) {
        return "127.0.0.1".equals(bind) || "localhost".equals(bind) || "::1".equals(bind);
    }
}
