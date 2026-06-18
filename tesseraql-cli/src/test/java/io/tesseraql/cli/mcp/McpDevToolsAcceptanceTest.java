package io.tesseraql.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 24 acceptance criterion, exercised end to end: an agent connected only over MCP
 * scaffolds a table-backed route and iterates until lint, tests, and coverage pass - never touching
 * the filesystem directly. Every framework action goes through a {@code tools/call} against the
 * server; the test only sets up the precondition (an app skeleton and a migrated database).
 */
class McpDevToolsAcceptanceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private int id;

    @Test
    void anAgentScaffoldsLintsAndTestsATableEntirelyOverMcp(@TempDir Path dir) throws Exception {
        // Precondition: an app exists and its database is migrated (the agent connects to it).
        Path app = dir.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));
        // DATABASE_TO_LOWER makes H2 fold unquoted identifiers to lowercase like PostgreSQL, so
        // the generated SQL's column labels match what the skeleton's suites assert.
        String jdbcUrl = "jdbc:h2:" + dir.resolve("demo-db")
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        migrate(app, jdbcUrl);

        McpServer server = new McpDevTools(app, false).toServer();

        // The agent initializes, then discovers the app.
        assertThat(initialize(server).get("serverInfo").get("name").asText())
                .isEqualTo("tesseraql-dev");
        JsonNode summary = callJson(server, "manifest_summary", Map.of());
        assertThat(summary.get("appName").asText()).isEqualTo("demo");

        // It introspects the table it intends to scaffold.
        JsonNode schema = callJson(server, "schema_introspect", Map.of("table", "items",
                "jdbcUrl", jdbcUrl));
        assertThat(schema.get("versionColumn").asText()).isEqualToIgnoringCase("version");

        // It scaffolds the CRUD slice - a write tool, applied through the checksum-aware writer.
        JsonNode scaffold = callJson(server, "scaffold_crud", Map.of("table", "items",
                "jdbcUrl", jdbcUrl));
        assertThat(scaffold.get("blocked").asBoolean()).isFalse();
        assertThat(scaffold.get("written")).isNotEmpty();

        // The new routes are now visible over MCP without any filesystem access.
        JsonNode afterScaffold = callJson(server, "manifest_summary", Map.of());
        assertThat(routeIds(afterScaffold)).contains("items.search", "items.table", "items.detail");

        // It lints: no errors.
        JsonNode lint = callJson(server, "lint", Map.of());
        assertThat(lint.get("errors").asLong())
                .as(() -> "lint findings: " + lint.get("findings")).isZero();

        // It runs the suites and the coverage gate: all green.
        JsonNode test = callJson(server, "test", Map.of("jdbcUrl", jdbcUrl));
        assertThat(test.get("passed").asBoolean())
                .as(() -> "test result: " + test).isTrue();
        assertThat(test.get("coverage").get("gatePassed").asBoolean()).isTrue();
        assertThat(test.get("tests").get("failed").asInt()).isZero();
    }

    @Test
    void readOnlyModeHidesTheWriteTools(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("ro");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("ro"));

        McpServer server = new McpDevTools(app, true).toServer();
        JsonNode tools = request(server, "tools/list", null).get("result").get("tools");
        java.util.List<String> names = new java.util.ArrayList<>();
        tools.forEach(tool -> names.add(tool.get("name").asText()));
        assertThat(names).contains("manifest_summary", "lint")
                .doesNotContain("scaffold_crud", "draft_save", "draft_apply");
        // The copilot prompt drives the write loop, so it is hidden in read-only mode too.
        assertThat(request(server, "initialize", mapper.createObjectNode())
                .get("result").get("capabilities").has("prompts")).isFalse();
        assertThat(request(server, "prompts/list", null).get("result").get("prompts")).isEmpty();
    }

    @Test
    void studioCopilotPromptGuidesTheDescribeToApplyLoop(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("copilot");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("copilot"));
        McpServer server = new McpDevTools(app, false).toServer();

        // The prompt is advertised (Studio backlog G: the describe -> draft -> preview -> apply loop).
        JsonNode prompts = request(server, "prompts/list", null).get("result").get("prompts");
        java.util.List<String> names = new java.util.ArrayList<>();
        prompts.forEach(prompt -> names.add(prompt.get("name").asText()));
        assertThat(names).contains("studio_copilot");

        // Getting it renders guidance that names the loop's tools and folds in the request.
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "studio_copilot");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("task", "a JSON endpoint that lists active users");
        arguments.put("table", "users");
        JsonNode result = request(server, "prompts/get", params).get("result");
        String text = result.get("messages").get(0).get("content").get("text").asText();
        assertThat(text).contains("a JSON endpoint that lists active users").contains("users")
                .contains("scaffold_crud").contains("draft_preview").contains("draft_apply");

        // A missing required argument is rejected, not rendered.
        ObjectNode bad = mapper.createObjectNode();
        bad.put("name", "studio_copilot");
        bad.set("arguments", mapper.createObjectNode());
        assertThat(request(server, "prompts/get", bad).has("error")).isTrue();
    }

    private void migrate(Path app, String jdbcUrl) throws Exception {
        String sql = Files.readString(app.resolve("db/migration/V1__create_items.sql"));
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    statement.execute(stmt);
                }
            }
        }
    }

    private JsonNode initialize(McpServer server) {
        return request(server, "initialize", mapper.createObjectNode()).get("result");
    }

    /** Calls a tool, asserts it did not error, and returns its structured content. */
    private JsonNode callJson(McpServer server, String tool, Map<String, Object> arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", mapper.valueToTree(arguments));
        JsonNode result = request(server, "tools/call", params).get("result");
        assertThat(result.get("isError").asBoolean())
                .as(() -> tool + " errored: " + result.get("content")).isFalse();
        return result.get("structuredContent");
    }

    private JsonNode request(McpServer server, String method, JsonNode params) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", ++id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return server.handle(message).orElseThrow();
    }

    private static java.util.List<String> routeIds(JsonNode summary) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        summary.get("routes").forEach(route -> ids.add(route.get("id").asText()));
        return ids;
    }
}
