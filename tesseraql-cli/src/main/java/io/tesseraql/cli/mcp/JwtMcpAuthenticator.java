package io.tesseraql.cli.mcp;

import io.tesseraql.mcp.McpAuthenticator;
import io.tesseraql.security.jwt.JwtAuthenticator;

/**
 * Bridges the MCP HTTP transport's auth seam to the framework's existing bearer-token check, so the
 * dev-tool server on a shared host honors the same {@code tesseraql.security.jwt} credentials as the
 * app. {@link JwtAuthenticator#authenticate} throws on rejection, which the transport maps to 401.
 */
final class JwtMcpAuthenticator implements McpAuthenticator {

    private final JwtAuthenticator delegate;

    JwtMcpAuthenticator(JwtAuthenticator delegate) {
        this.delegate = delegate;
    }

    @Override
    public void authenticate(String authorizationHeader) {
        delegate.authenticate(authorizationHeader);
    }
}
