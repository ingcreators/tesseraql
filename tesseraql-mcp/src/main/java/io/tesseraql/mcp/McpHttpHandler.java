package io.tesseraql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
    /** How long an idle MCP session stays valid. */
    static final java.time.Duration DEFAULT_TTL = java.time.Duration.ofHours(2);

    /** The ceiling behind the TTL, so a client that never issues DELETE cannot grow the map. */
    static final int MAX_SESSIONS = 10_000;

    /**
     * Session id to last-seen epoch millis.
     *
     * <p>It was a set with no expiry and no ceiling: {@code initialize} added an entry and only an
     * explicit {@code DELETE} ever removed one, so a client that reconnects instead of closing —
     * which is what a crashed or restarted client does — grew this without bound for the life of
     * the process.
     */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final java.time.Duration ttl;

    public McpHttpHandler(McpServer server, McpAuthenticator authenticator) {
        this(server, authenticator, DEFAULT_TTL);
    }

    /** Visible for tests, and for an embedder that wants a different idle window. */
    McpHttpHandler(McpServer server, McpAuthenticator authenticator, java.time.Duration ttl) {
        this.server = server;
        this.authenticator = authenticator;
        this.ttl = ttl;
    }

    /** Whether the session is known and still inside its idle window; touches it if so. */
    private boolean touch(String sessionId) {
        Long lastSeen = sessions.get(sessionId);
        if (lastSeen == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSeen > ttl.toMillis()) {
            sessions.remove(sessionId);
            return false;
        }
        sessions.put(sessionId, now);
        return true;
    }

    /** Drops expired entries, then the oldest if the ceiling is still reached. */
    private void prune() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> now - entry.getValue() > ttl.toMillis());
        while (sessions.size() >= MAX_SESSIONS) {
            sessions.entrySet().stream().min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .ifPresent(sessions::remove);
        }
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
        if (!initialize && request.sessionId() != null && !touch(request.sessionId())) {
            return json(404, errorBody("Unknown or expired session"), Map.of());
        }
        Optional<JsonNode> response = server.handle(message,
                new McpCallContext(request.authorization()));
        Map<String, String> headers = new LinkedHashMap<>();
        if (initialize) {
            String session = UUID.randomUUID().toString();
            prune();
            sessions.put(session, System.currentTimeMillis());
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
