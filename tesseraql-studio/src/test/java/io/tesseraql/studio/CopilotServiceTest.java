package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Studio copilot loop (roadmap Phase 44) against a scripted OpenAI-compatible mock (the
 * OIDC mock-OP precedent): the model's tool calls execute against the same gated Studio
 * paths a human uses, a save lands as a DRAFT plus an audit entry, and without an edit role
 * the write tool is refused.
 */
class CopilotServiceTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: copiloted
                """);
        Path api = dir.resolve("web/api/things");
        Files.createDirectories(api);
        Files.writeString(api.resolve("things.sql"), "select 1 as x\n");
        Files.writeString(api.resolve("get.yml"), """
                version: tesseraql/v1
                id: things.list
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                sql:
                  file: things.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        return dir;
    }

    /** A scripted chat-completions endpoint: pops one canned assistant message per call. */
    private String scriptedEndpoint(ConcurrentLinkedQueue<String> replies) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String message = replies.poll();
            byte[] body = ("{\"choices\":[{\"message\":" + message + "}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @Test
    void aToolCallExecutesAndTheDraftLandsWithAnAuditEntry(@TempDir Path dir)
            throws Exception {
        StudioService studio = new StudioService(new ManifestLoader().load(app(dir)), false);
        ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>(List.of(
                """
                        {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",
                         "function":{"name":"save_draft","arguments":
                         "{\\"path\\":\\"web/api/things/things.sql\\",\\"content\\":\\"select 2 as x\\\\n\\"}"}}]}
                        """
                        .replace("\n", ""),
                "{\"role\":\"assistant\",\"content\":\"Draft saved; review and apply.\"}"));
        CopilotService copilot = new CopilotService(studio,
                new ManifestLoader().load(dir), scriptedEndpoint(replies), "test-model",
                () -> "test-key", 4);

        List<CopilotService.Entry> transcript = copilot.send("alice",
                "bump the constant", true);

        assertThat(replies).isEmpty();
        assertThat(transcript).extracting(CopilotService.Entry::role)
                .containsExactly("user", "assistant", "assistant");
        assertThat(transcript.get(1).tool()).isEqualTo("save_draft");
        assertThat(transcript.get(2).text()).contains("review and apply");
        // The draft is real but NOT applied, and the audit trail names the chatting user.
        assertThat(studio.readDraft("web/api/things/things.sql")).contains("select 2");
        assertThat(studio.source("web/api/things/things.sql")).contains("select 1");
        assertThat(studio.auditEntries(10)).anySatisfy(entry -> {
            assertThat(entry.action()).isEqualTo("copilot");
            assertThat(entry.actor()).isEqualTo("alice");
        });
    }

    @Test
    void withoutAnEditRoleTheWriteToolIsRefusedAndReadsStillWork(@TempDir Path dir)
            throws Exception {
        StudioService studio = new StudioService(new ManifestLoader().load(app(dir)), false);
        CopilotService copilot = new CopilotService(studio,
                new ManifestLoader().load(dir), "http://127.0.0.1:9/unused", "m",
                () -> null, 2);

        // The tool bridge itself refuses the write when the session cannot edit...
        assertThat(copilot.executeTool("save_draft",
                "{\"path\":\"web/api/things/things.sql\",\"content\":\"x\"}", false, "bob"))
                .contains("Refused");
        assertThat(studio.readDraft("web/api/things/things.sql")).isNull();
        // ...while the read tools answer regardless.
        assertThat(copilot.executeTool("list_routes", "{}", false, "bob"))
                .contains("things.list");
        assertThat(copilot.executeTool("read_source",
                "{\"path\":\"web/api/things/things.sql\"}", false, "bob"))
                .contains("select 1");
        assertThat(copilot.executeTool("lint", "{}", false, "bob")).isNotBlank();
    }
}
