package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class McpHttpHandlerTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}";

    private McpServer server() {
        return McpServer.builder("http", "1.0")
                .tool(McpTool.builder("echo")
                        .handler((args, ctx) -> McpToolResult.text(args.path("text").asText()))
                        .build())
                .build();
    }

    private McpHttpHandler.Request post(String body, String session) {
        return new McpHttpHandler.Request("POST", null, session, null, body);
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

    @Test
    void anUnknownSessionIsRejected() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        assertThat(handler.handle(post(CALL, "bogus")).status()).isEqualTo(404);
    }

    @Test
    void deleteEndsTheSession() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        String session = handler.handle(post(INIT, null)).headers()
                .get(McpHttpHandler.SESSION_HEADER);

        McpHttpHandler.Response deleted = handler
                .handle(new McpHttpHandler.Request("DELETE", null, session, null, ""));
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(handler.handle(post(CALL, session)).status()).isEqualTo(404);
    }

    @Test
    void getIsNotAllowed() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response response = handler
                .handle(new McpHttpHandler.Request("GET", null, null, null, ""));
        assertThat(response.status()).isEqualTo(405);
        assertThat(response.headers()).containsKey("Allow");
    }

    @Test
    void aNotificationIsAccepted() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response response = handler.handle(
                post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null));
        assertThat(response.status()).isEqualTo(202);
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

        McpHttpHandler.Response accepted = handler
                .handle(new McpHttpHandler.Request("POST", "Bearer good", null, null, INIT));
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
