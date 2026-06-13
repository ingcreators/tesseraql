package io.tesseraql.cli.mcp;

import io.tesseraql.mcp.HttpTransport;
import io.tesseraql.mcp.McpAuthenticator;
import io.tesseraql.mcp.McpHttpHandler;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.mcp.StdioTransport;
import io.tesseraql.runtime.SecurityConfigFactory;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql mcp --app <dir>}: serves the developer MCP tools (roadmap Phase 24) so a coding
 * agent can scaffold, lint, test, inspect, and draft-edit the app over the Model Context Protocol.
 *
 * <p>Two transports: {@code stdio} (default - an agent launches this as a subprocess) and
 * {@code http} (a Streamable HTTP endpoint at {@code /mcp} for a shared development server). The
 * HTTP transport reuses the framework's bearer-token check ({@code tesseraql.security.jwt}); it
 * refuses to expose tools off-loopback without authentication unless {@code --insecure} is given.
 * {@code --read-only} drops the write tools (scaffold, drafts) for safe shared exposure.
 */
@Command(name = "mcp", description = "Serve the developer MCP tools over stdio or HTTP.")
public final class McpCommand implements Callable<Integer> {

    /** The supported MCP transports. */
    public enum Transport {
        stdio, http
    }

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

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
        McpServer server = new McpDevTools(app, readOnly).toServer();
        return transport == Transport.http ? serveHttp(server) : serveStdio(server);
    }

    private Integer serveStdio(McpServer server) throws IOException {
        // stdout carries protocol frames only; route everything else (logging, stray prints) to
        // stderr by swapping System.out before serving, keeping the real stream for the transport.
        PrintStream protocolOut = System.out;
        System.setOut(System.err);
        new StdioTransport(server, System.in, protocolOut).serve();
        return 0;
    }

    private Integer serveHttp(McpServer server) throws Exception {
        McpAuthenticator authenticator = authenticator();
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

    /** The framework's bearer-token verifier when the app configures a JWT secret, else null. */
    private McpAuthenticator authenticator() {
        SecurityConfig security = SecurityConfigFactory
                .build(new ManifestLoader().load(app).config());
        return security.jwt() == null
                ? null
                : new JwtMcpAuthenticator(new JwtAuthenticator(security.jwt()));
    }

    private static boolean isLoopback(String bind) {
        return "127.0.0.1".equals(bind) || "localhost".equals(bind) || "::1".equals(bind);
    }
}
