package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.inbox.InboxStore;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The inbox channel type end to end (roadmap Phase 49 slice 1): a recipient-addressed
 * {@code notify:} rides the outbox into {@code tql_user_notification} with rendered
 * title/body, redelivery dedupes on the event id, the Phase 48 opt-out silences it at
 * enqueue, and the read-state operations behave.
 */
@Testcontainers
class InboxDeliveryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal(
                "inbox-user", "inbox-user", "Inbox User", null, List.of(),
                List.of("ADMIN"), List.of(), Map.of()));
        sessionCookie = sessions.cookieName() + "=" + sid;
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void anAddressedNotificationLandsRenderedAndReadStateWorks() throws Exception {
        InboxStore inbox = inboxStore();
        int before = inbox.recent(null, "inbox-user", 100).size();

        assertThat(postCommand("decision=approved").statusCode()).isEqualTo(200);
        runtime.dispatchOutboxOnce();

        List<InboxStore.InboxMessage> messages = inbox.recent(null, "inbox-user", 100);
        assertThat(messages).hasSize(before + 1);
        InboxStore.InboxMessage message = messages.get(0);
        // The channel templates rendered against the payload, TEXT mode.
        assertThat(message.title()).isEqualTo("Your request was approved");
        assertThat(message.body()).contains("decided for inbox-user");
        assertThat(message.readAt()).isNull();
        assertThat(inbox.unreadCount(null, "inbox-user")).isEqualTo(1);

        // Read-state: someone else's mark is a no-op, the owner's sticks, all-read drains.
        assertThat(inbox.markRead(null, "not-the-owner", message.eventId())).isFalse();
        assertThat(inbox.markRead(null, "inbox-user", message.eventId())).isTrue();
        assertThat(inbox.unreadCount(null, "inbox-user")).isZero();
        assertThat(inbox.markAllRead(null, "inbox-user")).isZero();
    }

    /** At-least-once redelivery: the same outbox event id never doubles a message. */
    @Test
    void redeliveryDedupesOnTheEventId() {
        InboxStore inbox = inboxStore();
        int before = inbox.recent(null, "inbox-user", 100).size();

        inbox.deliver("dedupe-evt-1", null, "inbox-user", "approvals", "s", "once", null);
        inbox.deliver("dedupe-evt-1", null, "inbox-user", "approvals", "s", "twice", null);

        List<InboxStore.InboxMessage> messages = inbox.recent(null, "inbox-user", 100);
        assertThat(messages).hasSize(before + 1);
        assertThat(messages.stream().filter(m -> m.eventId().equals("dedupe-evt-1")))
                .hasSize(1).allMatch(m -> m.title().equals("once"));
        inbox.markRead(null, "inbox-user", "dedupe-evt-1");
    }

    /** The Phase 48 opt-out silences the inbox at enqueue: no outbox row, no message. */
    @Test
    void theOptOutSilencesTheInboxAtEnqueue() throws Exception {
        io.tesseraql.core.account.PreferenceStore preferences = runtime.camelContext()
                .getRegistry().lookupByNameAndType(TesseraqlProperties.PREFERENCE_STORE_BEAN,
                        io.tesseraql.core.account.PreferenceStore.class);
        InboxStore inbox = inboxStore();
        int before = inbox.recent(null, "inbox-user", 100).size();
        try {
            preferences.put(null, "inbox-user", "notify.approvals.optOut", "true");
            HttpResponse<String> response = postCommand("decision=denied");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("optedOut");
            runtime.dispatchOutboxOnce();
            assertThat(inbox.recent(null, "inbox-user", 100)).hasSize(before);
        } finally {
            preferences.remove(null, "inbox-user", "notify.approvals.optOut");
        }
    }

    private static InboxStore inboxStore() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.INBOX_STORE_BEAN, InboxStore.class);
    }

    private static HttpResponse<String> postCommand(String form) throws Exception {
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/decide"))
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", sessions.csrfTokenFromCookie(sessionCookie))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-inbox-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: inbox-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  notifications:
                    channels:
                      approvals:
                        type: inbox
                        title: "Your request was [(${payload.decision})]"
                        body: "[(${payload.decision})] decided for [(${payload.who})]."
                        userOptOut: "true"
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path decide = target.resolve("web/decide");
        Files.createDirectories(decide);
        Files.writeString(decide.resolve("post.yml"), """
                version: tesseraql/v1
                id: decide
                kind: route
                recipe: command-json
                input:
                  decision:
                    type: string
                    required: true
                    enum: [approved, denied]
                security:
                  auth: browser
                  csrf: true
                sql:
                  file: decide.sql
                  mode: update
                notify:
                  outcome:
                    channel: approvals
                    recipient: principal.subject
                    payload:
                      decision: body.decision
                      who: principal.loginId
                response:
                  json:
                    status: 200
                    body:
                      result: notify
                """);
        Files.writeString(decide.resolve("decide.sql"),
                "delete from tql_user_preference where tenant_id = '_never_'\n");
        return target;
    }
}
