package io.tesseraql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MCP Streamable HTTP transport, free of any specific HTTP server: it maps a request
 * ({@link Request}) to a {@link Response} over an {@link McpServer}. A JDK-server binding
 * ({@link HttpTransport}) drives it for the dev tool; a Camel route can drive the same handler to
 * serve app-declared MCP endpoints later (roadmap Phase 24).
 *
 * <p>{@code POST} carries one JSON-RPC message and gets the JSON-RPC response (or {@code 202} for a
 * notification). {@code initialize} mints an {@code Mcp-Session-Id} the client echoes on later
 * calls. {@code DELETE} ends a session. {@code GET} (the optional server-to-client SSE stream) is
 * not offered, so it answers {@code 405}. When an {@link McpAuthenticator} is configured every
 * request must carry a valid {@code Authorization} header, so the endpoint is safe to expose on a
 * shared server.
 */
public final class McpHttpHandler {

    /** The header naming the MCP session, issued at initialize and echoed on later requests. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String JSON = "application/json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpServer server;
    private final McpAuthenticator authenticator;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public McpHttpHandler(McpServer server, McpAuthenticator authenticator) {
        this.server = server;
        this.authenticator = authenticator;
    }

    /** Whether a credential is required on every request. */
    public boolean requiresAuth() {
        return authenticator != null;
    }

    public Response handle(Request request) {
        if (authenticator != null) {
            try {
                authenticator.authenticate(request.authorization());
            } catch (RuntimeException ex) {
                return json(401, "{\"error\":\"unauthorized\"}", Map.of("WWW-Authenticate",
                        "Bearer"));
            }
        }
        return switch (request.method().toUpperCase(java.util.Locale.ROOT)) {
            case "POST" -> post(request);
            case "DELETE" -> delete(request);
            default -> json(405, "{\"error\":\"method_not_allowed\"}", Map.of("Allow",
                    "POST, DELETE"));
        };
    }

    private Response post(Request request) {
        JsonNode message;
        try {
            message = mapper.readTree(request.body());
        } catch (Exception ex) {
            return json(400, errorBody("Parse error"), Map.of());
        }
        if (message == null) {
            return json(400, errorBody("Empty request body"), Map.of());
        }
        boolean initialize = message.path("method").asText("").equals("initialize");
        if (!initialize && request.sessionId() != null && !sessions.contains(request.sessionId())) {
            return json(404, errorBody("Unknown or expired session"), Map.of());
        }
        Optional<JsonNode> response = server.handle(message,
                new McpCallContext(request.authorization()));
        Map<String, String> headers = new LinkedHashMap<>();
        if (initialize) {
            String session = UUID.randomUUID().toString();
            sessions.add(session);
            headers.put(SESSION_HEADER, session);
        }
        if (response.isEmpty()) {
            return new Response(202, headers, "");
        }
        try {
            return json(200, mapper.writeValueAsString(response.get()), headers);
        } catch (Exception ex) {
            return json(500, errorBody("Serialization error"), Map.of());
        }
    }

    private Response delete(Request request) {
        if (request.sessionId() != null) {
            sessions.remove(request.sessionId());
        }
        return new Response(204, Map.of(), "");
    }

    private String errorBody(String message) {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", message);
        return body.toString();
    }

    private Response json(int status, String body, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", JSON);
        headers.putAll(extraHeaders);
        return new Response(status, headers, body);
    }

    /** A transport-neutral inbound request. {@code sessionId}/{@code protocolVersion} may be null. */
    public record Request(String method, String authorization, String sessionId,
            String protocolVersion, String body) {
    }

    /** A transport-neutral response: HTTP status, headers, and a (possibly empty) body. */
    public record Response(int status, Map<String, String> headers, String body) {

        public Response {
            headers = Map.copyOf(headers);
        }
    }
}
