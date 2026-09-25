package io.tesseraql.mcp;

import io.tesseraql.core.error.ErrorEnvelope;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP Streamable HTTP transport, free of any specific HTTP server: it maps a request
 * ({@link Request}) to a {@link Response} over an {@link McpServer}. A JDK-server binding
 * ({@link HttpTransport}) drives it for the dev tool; the runtime's {@code /_tesseraql/mcp} route
 * drives the same handler for app-declared MCP endpoints.
 *
 * <p>{@code POST} carries one JSON-RPC message or a batch and gets the JSON-RPC response (or
 * {@code 202} for a notification). {@code initialize} mints an {@code Mcp-Session-Id} the client
 * echoes on every later request. {@code DELETE} ends a session. {@code GET} (the optional
 * server-to-client SSE stream) is not offered, so it answers {@code 405}. When an
 * {@link McpAuthenticator} is configured every request must carry a valid {@code Authorization}
 * header, so the endpoint is safe to expose on a shared server.
 *
 * <p>The transport judges the caller before the message (docs/audit-low-leads.md, G1). A present
 * {@code Origin} must be a loopback origin or one the embedder allowed — the MCP specification's
 * MUST against DNS rebinding, which makes a web page same-origin to a local server; absent means
 * a non-browser client. A {@code POST} must declare {@code application/json}: the one content type
 * a browser cannot send without a preflight, which closes the no-cors simple request that ran the
 * write tools from any page open beside the dev server. A {@code MCP-Protocol-Version} the server
 * does not speak is {@code 400}, as revision 2025-06-18 requires. And a request after
 * {@code initialize} must carry the session it was given.
 *
 * <p>Two vocabularies, by layer: a refusal of the HTTP request (origin, credential, method, content
 * type, revision, session, size) is the framework's coded envelope with a {@code TQL-MCP} code; a
 * failure of the JSON-RPC message (unparseable, unserializable) is a JSON-RPC error object, the
 * way the stdio transport answers the same failure.
 */
public final class McpHttpHandler {

    /** The header naming the MCP session, issued at initialize and echoed on later requests. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";
    /** The header naming the negotiated revision on requests after initialize (2025-06-18). */
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final String JSON = "application/json; charset=utf-8";

    private final ObjectMapper mapper = McpJson.constrained();
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
    /**
     * The 401 challenge. Bare {@code Bearer} unless the embedder supplies the RFC 9728
     * {@code resource_metadata} form — the parameter Claude requires to discover which
     * authorization server issues for this resource (docs/audit-hardening.md decision 2).
     */
    private final String challenge;
    /** Origins admitted beside loopback, normalised by {@link #normalizeOrigin}. */
    private final Set<String> allowedOrigins;

    public McpHttpHandler(McpServer server, McpAuthenticator authenticator) {
        this(server, authenticator, "Bearer", DEFAULT_TTL, Set.of());
    }

    public McpHttpHandler(McpServer server, McpAuthenticator authenticator, String challenge) {
        this(server, authenticator, challenge, DEFAULT_TTL, Set.of());
    }

    /**
     * With the origins a browser may call from beside loopback: the runtime passes its own
     * external origin, the dev server what {@code --allow-origin} named. Each must parse as
     * {@code scheme://host[:port]}.
     */
    public McpHttpHandler(McpServer server, McpAuthenticator authenticator, String challenge,
            Collection<String> allowedOrigins) {
        this(server, authenticator, challenge, DEFAULT_TTL, allowedOrigins);
    }

    /** Visible for tests, and for an embedder that wants a different idle window. */
    McpHttpHandler(McpServer server, McpAuthenticator authenticator, java.time.Duration ttl) {
        this(server, authenticator, "Bearer", ttl, Set.of());
    }

    private McpHttpHandler(McpServer server, McpAuthenticator authenticator, String challenge,
            java.time.Duration ttl, Collection<String> allowedOrigins) {
        this.server = server;
        this.authenticator = authenticator;
        this.challenge = challenge;
        this.ttl = ttl;
        Set<String> normalized = new LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            String canonical = normalizeOrigin(origin);
            if (canonical == null) {
                throw new IllegalArgumentException("Not an origin (scheme://host[:port]): "
                        + origin);
            }
            normalized.add(canonical);
        }
        this.allowedOrigins = Set.copyOf(normalized);
    }

    /** Whether the session is known and still inside its idle window; touches it if so. */
    private boolean touch(String sessionId) {
        return touch(sessions, sessionId, System.currentTimeMillis(), ttl.toMillis());
    }

    /**
     * The refresh itself, over its map — package-private so the concurrent case has a seam a
     * deterministic test can reach.
     *
     * <p>The write is {@code computeIfPresent}, not {@code put}. The read and the write are two
     * operations, and {@code DELETE /mcp} removes the entry between them often enough to matter:
     * an unconditional put re-adds what the client just terminated, and the session then lives on
     * to its idle window. The remapping runs under the map's per-entry lock, so the entry is
     * either still there and refreshed, or gone and reported gone.
     */
    static boolean touch(Map<String, Long> sessions, String sessionId, long now, long ttlMillis) {
        Long lastSeen = sessions.get(sessionId);
        if (lastSeen == null) {
            return false;
        }
        if (now - lastSeen > ttlMillis) {
            sessions.remove(sessionId);
            return false;
        }
        return sessions.computeIfPresent(sessionId, (id, seen) -> now) != null;
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

    /** TQL-MCP-4263: the transport bearer was missing or refused (HTTP 401). */
    private static final TqlErrorCode UNAUTHORIZED = new TqlErrorCode(TqlDomain.MCP, 4263);
    /** TQL-MCP-4264: the MCP endpoint takes POST and DELETE only (HTTP 405). */
    private static final TqlErrorCode METHOD_NOT_ALLOWED = new TqlErrorCode(TqlDomain.MCP, 4264);
    /**
     * TQL-MCP-4265: the request's Origin is neither loopback nor one the server allows — a web
     * page, not an MCP client, is calling (HTTP 403).
     */
    private static final TqlErrorCode ORIGIN_REFUSED = new TqlErrorCode(TqlDomain.MCP, 4265);
    /** TQL-MCP-4266: a POST to the MCP endpoint must declare application/json (HTTP 415). */
    private static final TqlErrorCode NOT_JSON = new TqlErrorCode(TqlDomain.MCP, 4266);
    /** TQL-MCP-4267: the MCP-Protocol-Version header names a revision not served (HTTP 400). */
    private static final TqlErrorCode UNSUPPORTED_REVISION = new TqlErrorCode(TqlDomain.MCP,
            4267);
    /** TQL-MCP-4268: a request after initialize carried no Mcp-Session-Id (HTTP 400). */
    private static final TqlErrorCode SESSION_REQUIRED = new TqlErrorCode(TqlDomain.MCP, 4268);
    /** TQL-MCP-4269: the Mcp-Session-Id names no live session — unknown or idle too long (HTTP 404). */
    private static final TqlErrorCode SESSION_UNKNOWN = new TqlErrorCode(TqlDomain.MCP, 4269);
    /** TQL-MCP-4270: the request body exceeds the MCP transport's ceiling (HTTP 413). */
    private static final TqlErrorCode BODY_TOO_LARGE = new TqlErrorCode(TqlDomain.MCP, 4270);

    public Response handle(Request request) {
        if (!originAllowed(request.origin())) {
            return json(403, ErrorEnvelope.json(ORIGIN_REFUSED,
                    "The MCP endpoint does not answer a browser page at this origin"), Map.of());
        }
        if (authenticator != null) {
            try {
                authenticator.authenticate(request.authorization());
            } catch (RuntimeException ex) {
                // The framework envelope, coded: this endpoint shipped the flat
                // {"error":"…"} shape the federation endpoints retired — a string no
                // operator could search for (docs/duplication-consolidation.md, campaign 3).
                return json(401, ErrorEnvelope.json(UNAUTHORIZED,
                        "The MCP transport requires a valid bearer token"),
                        Map.of("WWW-Authenticate", challenge));
            }
        }
        return switch (request.method().toUpperCase(Locale.ROOT)) {
            case "POST" -> post(request);
            case "DELETE" -> delete(request);
            default -> json(405, ErrorEnvelope.json(METHOD_NOT_ALLOWED,
                    "The MCP endpoint takes POST and DELETE"),
                    Map.of("Allow",
                            "POST, DELETE"));
        };
    }

    /** The 413 a transport answers when it stops reading a body at its ceiling. */
    Response bodyTooLarge(long maxBodyBytes) {
        return json(413, ErrorEnvelope.json(BODY_TOO_LARGE,
                "The MCP transport reads at most " + maxBodyBytes + " bytes of request body"),
                Map.of());
    }

    private Response post(Request request) {
        if (!isJson(request.contentType())) {
            return json(415, ErrorEnvelope.json(NOT_JSON,
                    "The MCP endpoint takes application/json"), Map.of());
        }
        if (request.protocolVersion() != null && !McpServer.supports(request.protocolVersion())) {
            return json(400, ErrorEnvelope.json(UNSUPPORTED_REVISION,
                    "MCP-Protocol-Version " + request.protocolVersion() + " is not served"),
                    Map.of());
        }
        JsonNode message;
        try {
            message = mapper.readTree(request.body());
        } catch (Exception ex) {
            return json(400, server.parseError(detail(ex)).toString(), Map.of());
        }
        if (message.isMissingNode()) {
            // An empty or blank body parses to nothing at all, which is not a JSON-RPC message
            // either; the null check it replaces was dead, since readTree never returns null.
            return json(400, server.parseError("empty request body").toString(), Map.of());
        }
        boolean initialize = message.path("method").asString("").equals("initialize");
        if (!initialize) {
            if (request.sessionId() == null) {
                return json(400, ErrorEnvelope.json(SESSION_REQUIRED,
                        "A request after initialize must carry its Mcp-Session-Id"), Map.of());
            }
            if (!touch(request.sessionId())) {
                return json(404, ErrorEnvelope.json(SESSION_UNKNOWN,
                        "The Mcp-Session-Id names no live session; initialize again"),
                        Map.of());
            }
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
            return json(500, server.internalError(detail(ex)).toString(), Map.of());
        }
    }

    private Response delete(Request request) {
        if (request.sessionId() != null) {
            sessions.remove(request.sessionId());
        }
        return new Response(204, Map.of(), "");
    }

    private static String detail(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.toString();
    }

    /** Whether a declared content type is JSON: the media type alone, parameters and case aside. */
    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String mediaType = (parameters < 0 ? contentType : contentType.substring(0, parameters))
                .trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType);
    }

    /**
     * Whether the caller's {@code Origin} admits it: absent (a browser sends one on every POST,
     * so its absence means a non-browser client), a loopback origin on any port (a local client
     * such as an inspector page), or one the embedder allowed. The opaque {@code null} origin
     * and anything unparseable are refused.
     */
    boolean originAllowed(String origin) {
        if (origin == null) {
            return true;
        }
        String canonical = normalizeOrigin(origin);
        if (canonical == null) {
            return false;
        }
        String host = URI.create(canonical).getHost();
        return isLoopback(host) || allowedOrigins.contains(canonical);
    }

    /**
     * {@code scheme://host[:port]} with the scheme and host lower-cased and a default port
     * dropped, the way a browser spells {@code Origin}; null when {@code origin} is not one.
     */
    static String normalizeOrigin(String origin) {
        String trimmed = origin == null ? "" : origin.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException notAUri) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawPath() != null
                && !uri.getRawPath().isEmpty() || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || "http".equals(scheme) && port == 80
                || "https".equals(scheme) && port == 443;
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || host.startsWith("127.") || "[::1]".equals(host);
    }

    private Response json(int status, String body, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", JSON);
        headers.putAll(extraHeaders);
        return new Response(status, headers, body);
    }

    /**
     * A transport-neutral inbound request: the method, the {@code Authorization},
     * {@code Mcp-Session-Id}, {@code MCP-Protocol-Version}, {@code Origin} and {@code Content-Type}
     * headers (each null when absent), and the body.
     */
    public record Request(String method, String authorization, String sessionId,
            String protocolVersion, String origin, String contentType, String body) {
    }

    /** A transport-neutral response: HTTP status, headers, and a (possibly empty) body. */
    public record Response(int status, Map<String, String> headers, String body) {

        public Response {
            headers = Map.copyOf(headers);
        }
    }
}
