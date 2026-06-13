package io.tesseraql.mcp;

/**
 * Authenticates an HTTP {@code Authorization} header for the Streamable HTTP transport. The core
 * stays auth-agnostic: a caller plugs in verification (for the dev tool, the framework's existing
 * bearer-token check) so the same transport can front a shared development server.
 *
 * <p>Implementations throw on rejection - any exception means "unauthorized" and the transport
 * answers {@code 401}. A null or blank header is a rejection too.
 */
@FunctionalInterface
public interface McpAuthenticator {

    /**
     * Verifies the {@code Authorization} header value (for example {@code "Bearer <jwt>"}).
     *
     * @throws RuntimeException if the credential is missing, malformed, or invalid
     */
    void authenticate(String authorizationHeader);
}
