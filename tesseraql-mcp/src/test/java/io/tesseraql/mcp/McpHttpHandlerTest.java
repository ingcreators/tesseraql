package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpHttpHandlerTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}";

    private McpServer server() {
        return McpServer.builder("http", "1.0")
                .tool(McpTool.builder("echo")
                        .handler((args, ctx) -> McpToolResult.text(args.path("text").asString()))
                        .build())
                .build();
    }

    private McpHttpHandler.Request post(String body, String session) {
        return new McpHttpHandler.Request("POST", null, session, null, null, JSON, body);
    }

    private static final String JSON = "application/json";

    /** An initialized session on {@code handler}. */
    private static String session(McpHttpHandler handler) {
        return handler.handle(new McpHttpHandler.Request("POST", null, null, null, null, JSON,
                INIT)).headers().get(McpHttpHandler.SESSION_HEADER);
    }

    private static tools.jackson.databind.JsonNode body(McpHttpHandler.Response response) {
        try {
            return new JsonMapper().readTree(response.body());
        } catch (Exception ex) {
            throw new AssertionError(response.body(), ex);
        }
    }

    @Test
    void initializeMintsASessionThatLaterCallsReuse() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);

        McpHttpHandler.Response init = handler.handle(post(INIT, null));
        assertThat(init.status()).isEqualTo(200);
        String session = init.headers().get(McpHttpHandler.SESSION_HEADER);
        assertThat(session).isNotBlank();
        assertThat(init.body()).contains("\"protocolVersion\"");

        McpHttpHandler.Response call = handler.handle(post(CALL, session));
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.body()).contains("\"text\":\"hi\"");
    }

    /**
     * A session that goes idle stops working, and the map stops growing.
     *
     * <p>It was a set with no expiry and no ceiling: {@code initialize} added an entry and only an
     * explicit {@code DELETE} removed one. A client that reconnects rather than closing — which is
     * what a crashed or restarted one does — grew it for the life of the process.
     */
    @Test
    void anIdleSessionExpires() throws Exception {
        McpHttpHandler handler = new McpHttpHandler(server(), null,
                java.time.Duration.ofMillis(40));
        String session = handler.handle(post(INIT, null)).headers()
                .get(McpHttpHandler.SESSION_HEADER);
        assertThat(handler.handle(post(CALL, session)).status()).isEqualTo(200);

        Thread.sleep(80);

        assertThat(handler.handle(post(CALL, session)).status()).isEqualTo(404);
    }

    @Test
    void useKeepsASessionAlive() throws Exception {
        McpHttpHandler handler = new McpHttpHandler(server(), null,
                java.time.Duration.ofMillis(120));
        String session = handler.handle(post(INIT, null)).headers()
                .get(McpHttpHandler.SESSION_HEADER);

        // Three calls spanning more than one TTL: the window is idleness, not total age.
        for (int i = 0; i < 3; i++) {
            Thread.sleep(50);
            assertThat(handler.handle(post(CALL, session)).status()).isEqualTo(200);
        }
    }

    /** The refusal is coded: it shipped as a flat body the envelope ledger cannot see. */
    @Test
    void anUnknownSessionIsRejected() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response refused = handler.handle(post(CALL, "bogus"));
        assertThat(refused.status()).isEqualTo(404);
        assertThat(body(refused).path("error").path("code").asString()).isEqualTo("TQL-MCP-4269");
    }

    /**
     * A request after {@code initialize} carries the session it was given, or it is refused
     * before dispatch — the session was optional, so a POST that never initialized reached
     * {@code tools/call} (docs/audit-low-leads.md, G1).
     */
    @Test
    void aRequestAfterInitializeNeedsItsSession() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response refused = handler.handle(post(CALL, null));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(body(refused).path("error").path("code").asString()).isEqualTo("TQL-MCP-4268");
        assertThat(refused.body()).doesNotContain("\"text\":\"hi\"");
    }

    @Test
    void deleteEndsTheSession() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        String session = session(handler);

        McpHttpHandler.Response deleted = handler.handle(
                new McpHttpHandler.Request("DELETE", null, session, null, null, null, ""));
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(handler.handle(post(CALL, session)).status()).isEqualTo(404);
    }

    @Test
    void getIsNotAllowed() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response response = handler
                .handle(new McpHttpHandler.Request("GET", null, null, null, null, null, ""));
        assertThat(response.status()).isEqualTo(405);
        assertThat(response.headers()).containsKey("Allow");
    }

    @Test
    void aNotificationIsAccepted() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response response = handler.handle(
                post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                        session(handler)));
        assertThat(response.status()).isEqualTo(202);
    }

    // ----- the caller is judged before the message (docs/audit-low-leads.md, G1) -----

    private static McpHttpHandler.Request from(String origin, String contentType, String body,
            String session) {
        return new McpHttpHandler.Request("POST", null, session, null, origin, contentType, body);
    }

    /**
     * A web page's request names its origin, and a page on another host — the DNS-rebinding
     * page the specification's MUST is about — is refused before the credential, the method
     * or the body is looked at. A no-JWT loopback dev server ran the write tools from such a
     * page.
     */
    @Test
    void aForeignOriginIsRefusedBeforeAnything() {
        McpHttpHandler handler = new McpHttpHandler(server(), header -> {
            throw new IllegalArgumentException("would be 401");
        });
        McpHttpHandler.Response refused = handler.handle(
                from("http://evil.example", "text/plain", CALL, null));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(body(refused).path("error").path("code").asString()).isEqualTo("TQL-MCP-4265");
        assertThat(refused.headers()).doesNotContainKey("WWW-Authenticate");
    }

    /** A local client page — an inspector on another loopback port — is not a foreign page. */
    @Test
    void aLoopbackOriginOnAnyPortIsAdmitted() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        for (String origin : java.util.List.of("http://localhost:6274", "http://127.0.0.1:8765",
                "http://[::1]:1", "https://LOCALHOST")) {
            assertThat(handler.handle(from(origin, JSON, INIT, null)).status())
                    .as(origin).isEqualTo(200);
        }
    }

    /** The embedder's allowed origins compare the way a browser spells {@code Origin}. */
    @Test
    void anAllowedOriginIsAdmittedInItsBrowserSpelling() {
        McpHttpHandler handler = new McpHttpHandler(server(), null, "Bearer",
                java.util.List.of("https://App.Example:443", "http://dev.example:3000"));
        assertThat(handler.handle(from("https://app.example", JSON, INIT, null)).status())
                .isEqualTo(200);
        assertThat(handler.handle(from("http://dev.example:3000", JSON, INIT, null)).status())
                .isEqualTo(200);
        assertThat(handler.handle(from("https://app.example:8443", JSON, INIT, null)).status())
                .isEqualTo(403);
        assertThat(handler.handle(from("http://app.example", JSON, INIT, null)).status())
                .isEqualTo(403);
    }

    /** An origin the embedder cannot have meant is refused at construction, not at the first call. */
    @Test
    void anAllowedOriginThatIsNotOneIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new McpHttpHandler(server(), null, "Bearer",
                java.util.List.of("app.example/path")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.example/path");
    }

    /** The opaque origin a sandboxed frame sends is not a client either. */
    @Test
    void theNullOriginIsRefused() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        assertThat(handler.handle(from("null", JSON, INIT, null)).status()).isEqualTo(403);
    }

    /**
     * {@code application/json} is the one content type a browser cannot send without a
     * preflight, so demanding it closes the no-cors simple request; parameters and case are
     * not the media type.
     */
    @Test
    void aPostThatDoesNotDeclareJsonIsRefused() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        for (String contentType : java.util.Arrays.asList("text/plain",
                "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", null)) {
            McpHttpHandler.Response refused = handler.handle(from(null, contentType, INIT, null));
            assertThat(refused.status()).as(String.valueOf(contentType)).isEqualTo(415);
            assertThat(body(refused).path("error").path("code").asString())
                    .isEqualTo("TQL-MCP-4266");
        }
        for (String contentType : java.util.List.of("application/json",
                "application/json; charset=utf-8", "Application/JSON")) {
            assertThat(handler.handle(from(null, contentType, INIT, null)).status())
                    .as(contentType).isEqualTo(200);
        }
    }

    /**
     * Revision 2025-06-18: a request naming a revision the server does not serve is 400. The
     * header was read into the request and consulted by nothing (docs/audit-low-leads.md, G3).
     */
    @Test
    void anUnsupportedProtocolVersionHeaderIsRefused() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        String session = session(handler);
        McpHttpHandler.Response refused = handler.handle(new McpHttpHandler.Request("POST", null,
                session, "1999-01-01", null, JSON, CALL));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(body(refused).path("error").path("code").asString()).isEqualTo("TQL-MCP-4267");

        assertThat(handler.handle(new McpHttpHandler.Request("POST", null, session, "2025-06-18",
                null, JSON, CALL)).status()).isEqualTo(200);
        assertThat(handler.handle(new McpHttpHandler.Request("POST", null, session, "2025-03-26",
                null, JSON, CALL)).status()).isEqualTo(200);
    }

    /**
     * A body that is not JSON is the JSON-RPC parse error the stdio transport already sends,
     * not a flat body; an empty body is the same failure, not a 200 (docs/audit-low-leads.md,
     * G7 — the empty-body branch was dead: readTree never returns null).
     */
    @Test
    void anUnparseableOrEmptyBodyIsAJsonRpcParseError() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        for (String raw : java.util.List.of("{bad", "", "   ")) {
            McpHttpHandler.Response refused = handler.handle(post(raw, null));
            assertThat(refused.status()).as(raw).isEqualTo(400);
            tools.jackson.databind.JsonNode error = body(refused);
            assertThat(error.path("jsonrpc").asString()).isEqualTo("2.0");
            assertThat(error.path("id").isNull()).isTrue();
            assertThat(error.path("error").path("code").asInt()).isEqualTo(McpServer.PARSE_ERROR);
        }
    }

    /** A batch rides through the transport as one request and one array answer. */
    @Test
    void aBatchIsAnsweredAsAnArrayAndABatchOfNotificationsAsNothing() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        String session = session(handler);
        McpHttpHandler.Response answered = handler.handle(post(
                "[{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"},"
                        + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}]",
                session));
        assertThat(answered.status()).isEqualTo(200);
        assertThat(body(answered).isArray()).isTrue();
        assertThat(body(answered)).hasSize(2);

        McpHttpHandler.Response silent = handler.handle(post(
                "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]", session));
        assertThat(silent.status()).isEqualTo(202);
    }

    @Test
    void aConfiguredAuthenticatorGatesEveryRequest() {
        McpAuthenticator auth = header -> {
            if (!"Bearer good".equals(header)) {
                throw new IllegalArgumentException("bad token");
            }
        };
        McpHttpHandler handler = new McpHttpHandler(server(), auth);
        assertThat(handler.requiresAuth()).isTrue();

        McpHttpHandler.Response rejected = handler.handle(post(INIT, null));
        assertThat(rejected.status()).isEqualTo(401);
        assertThat(rejected.headers()).containsKey("WWW-Authenticate");

        McpHttpHandler.Response accepted = handler.handle(new McpHttpHandler.Request("POST",
                "Bearer good", null, null, null, JSON, INIT));
        assertThat(accepted.status()).isEqualTo(200);
    }

    /**
     * A map that drops the entry the moment it is read — the DELETE landing in the window between
     * {@code touch}'s read and its write, deterministically rather than by racing threads.
     */
    private static final class DeletedOnRead extends ConcurrentHashMap<String, Long> {
        private static final long serialVersionUID = 1L;

        @Override
        public Long get(Object key) {
            Long seen = super.get(key);
            super.remove(key);
            return seen;
        }
    }

    /**
     * {@code DELETE /mcp} terminates a session; a request already in flight must not bring it
     * back. {@code touch} read the entry and then wrote it back unconditionally, so a delete
     * arriving between the two was silently undone and the terminated session lived on to its
     * idle window.
     */
    @Test
    void aSessionDeletedWhileARequestIsInFlightIsNotResurrected() {
        Map<String, Long> sessions = new DeletedOnRead();
        long now = System.currentTimeMillis();
        sessions.put("session-1", now);

        boolean alive = McpHttpHandler.touch(sessions, "session-1", now, 7_200_000L);

        assertThat(alive)
                .as("the session was deleted mid-request; the request must not report it live")
                .isFalse();
        assertThat(sessions)
                .as("and must not put it back")
                .doesNotContainKey("session-1");
    }

    /** The ordinary path still refreshes, so the fix is not "always report gone". */
    @Test
    void touchRefreshesALiveSession() {
        Map<String, Long> sessions = new ConcurrentHashMap<>();
        sessions.put("session-2", 1_000L);

        assertThat(McpHttpHandler.touch(sessions, "session-2", 2_000L, 7_200_000L)).isTrue();
        assertThat(sessions).containsEntry("session-2", 2_000L);
    }

    /** And an idle session is still dropped rather than refreshed. */
    @Test
    void touchDropsASessionPastItsIdleWindow() {
        Map<String, Long> sessions = new ConcurrentHashMap<>();
        sessions.put("session-3", 1_000L);

        assertThat(McpHttpHandler.touch(sessions, "session-3", 10_000L, 5_000L)).isFalse();
        assertThat(sessions).doesNotContainKey("session-3");
    }
}
