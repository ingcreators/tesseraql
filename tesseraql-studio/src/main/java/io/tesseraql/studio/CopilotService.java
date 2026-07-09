package io.tesseraql.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Studio copilot (roadmap Phase 44, decision point 8 resolved): a chat loop against an
 * <b>operator-configured</b> OpenAI-compatible chat-completions endpoint — TesseraQL ships no
 * model and stores no key in app source (the credential resolves through the config's lazy
 * placeholder chain at call time). The model drives the framework's existing gated loop as
 * tools: read the manifest and sources, lint, inspect the schema, preview a buffer, and save
 * a DRAFT — never apply. A draft is not served until a human reviews and applies it in the
 * editor's diff-confirm UI, so every mutation stays a separately gated, audited step.
 */
public final class CopilotService {

    private static final TqlErrorCode COPILOT = new TqlErrorCode(TqlDomain.STUDIO, 4235);
    private static final TqlErrorCode UNKNOWN_TURN = new TqlErrorCode(TqlDomain.STUDIO, 4040);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONVERSATIONS = 32;
    private static final int MAX_MESSAGES = 60;
    private static final int MAX_PENDING_TURNS = 64;
    private static final long TURN_TTL_MILLIS = 120_000;

    private final StudioService studio;
    private final AppManifest manifest;
    private final String endpoint;
    private final String model;
    private final Supplier<String> apiKey;
    private final int maxTurns;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** Per-actor conversation history, bounded both ways (a demo panel, not a datastore). */
    private final Map<String, List<JsonNode>> conversations = java.util.Collections
            .synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f,
                    true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<JsonNode>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /**
     * Pending streaming turns (docs/copilot.md "The turn contract"): the htmx send registers
     * the message here and the SSE stream consumes it exactly once. The id is the capability
     * — random, actor-bound, short-lived — and the map is bounded like the conversations.
     */
    private final Map<String, PendingTurn> turns = java.util.Collections
            .synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingTurn> eldest) {
                    return size() > MAX_PENDING_TURNS;
                }
            });

    private record PendingTurn(String actor, String message, boolean canEdit,
            long createdMillis) {
    }

    /** Streaming callbacks for one turn: content deltas and tool-execution markers. */
    public interface TurnListener {
        void delta(String text);

        void toolCall(String name);
    }

    public CopilotService(StudioService studio, AppManifest manifest, String endpoint,
            String model, Supplier<String> apiKey, int maxTurns) {
        this.studio = studio;
        this.manifest = manifest;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.maxTurns = Math.max(1, maxTurns);
    }

    /** One rendered chat entry for the panel: who, the text, and any tool call it made. */
    public record Entry(String role, String text, String tool) {
    }

    /** Sends one user message, runs the bounded tool loop, and returns the full transcript. */
    public List<Entry> send(String actor, String message, boolean canEdit) {
        List<JsonNode> history = conversations.computeIfAbsent(actor,
                key -> new ArrayList<>(List.of(systemMessage())));
        synchronized (history) {
            history.add(chatMessage("user", message));
            try {
                loop(history, canEdit, actor, null);
            } catch (TqlException ex) {
                throw ex;
            } catch (Exception ex) {
                history.add(chatMessage("assistant",
                        "The model endpoint failed: " + ex.getMessage()));
            }
            trim(history);
            return transcript(history);
        }
    }

    /**
     * Registers a single-use pending turn for the actor and returns its id — the htmx send
     * path (docs/copilot.md): the model is not called until the SSE stream consumes it.
     */
    public String begin(String actor, String message, boolean canEdit) {
        String id = java.util.UUID.randomUUID().toString();
        turns.put(id, new PendingTurn(actor, message, canEdit, System.currentTimeMillis()));
        return id;
    }

    /** One consumed pending turn, ready to run. */
    public record Turn(String message, boolean canEdit) {
    }

    /**
     * Consumes a pending turn — exactly once. An unknown, foreign, expired, or already-used
     * id is a 404: the id is the capability. Validation happens here, BEFORE the caller
     * commits the SSE response, because a stream cannot change its status once open.
     */
    public Turn consumeTurn(String turnId, String actor) {
        PendingTurn turn = turnId == null ? null : turns.remove(turnId);
        if (turn == null || !turn.actor().equals(actor)
                || System.currentTimeMillis() - turn.createdMillis() > TURN_TTL_MILLIS) {
            throw new TqlException(UNKNOWN_TURN, "Unknown or expired copilot turn");
        }
        return new Turn(turn.message(), turn.canEdit());
    }

    /**
     * Runs one consumed turn through the bounded tool loop, streaming through the listener;
     * returns the entries this turn appended (the {@code done} payload — the user item is
     * excluded, the send fragment already rendered it). Unlike {@link #send}, a model
     * failure never throws: the stream is already open, so the failure lands in the history
     * as an assistant entry and rides the {@code done} event like any other reply.
     *
     * <p>Like {@link #send}, the actor's history lock is held for the whole turn, so a
     * concurrent page render waits for the stream to finish.
     */
    public List<Entry> runTurn(String actor, Turn turn, TurnListener listener) {
        List<JsonNode> history = conversations.computeIfAbsent(actor,
                key -> new ArrayList<>(List.of(systemMessage())));
        synchronized (history) {
            int before = history.size();
            history.add(chatMessage("user", turn.message()));
            try {
                loop(history, turn.canEdit(), actor, listener);
            } catch (Exception ex) {
                history.add(chatMessage("assistant",
                        "The model endpoint failed: " + ex.getMessage()));
            }
            List<Entry> appended = transcript(history.subList(before + 1, history.size()));
            trim(history);
            return appended;
        }
    }

    /** The transcript for rendering (system and raw tool payloads folded away). */
    public List<Entry> transcript(String actor) {
        List<JsonNode> history = conversations.get(actor);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return transcript(history);
        }
    }

    /** Starts the actor's conversation over. */
    public void reset(String actor) {
        conversations.remove(actor);
    }

    /** The bounded tool loop; a non-null listener makes each completion stream its deltas. */
    private void loop(List<JsonNode> history, boolean canEdit, String actor,
            TurnListener listener) throws Exception {
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonNode reply = listener == null
                    ? complete(history, canEdit)
                    : completeStreaming(history, canEdit, listener);
            history.add(reply);
            JsonNode toolCalls = reply.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return;
            }
            for (JsonNode call : toolCalls) {
                String name = call.path("function").path("name").asText();
                String arguments = call.path("function").path("arguments").asText("{}");
                if (listener != null) {
                    listener.toolCall(name);
                }
                String result = executeTool(name, arguments, canEdit, actor);
                ObjectNode toolMessage = MAPPER.createObjectNode();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", call.path("id").asText());
                toolMessage.put("content", result);
                history.add(toolMessage);
            }
        }
        history.add(chatMessage("assistant",
                "Stopped after " + maxTurns + " tool turns; narrow the request and retry."));
    }

    private JsonNode complete(List<JsonNode> history, boolean canEdit) throws Exception {
        HttpResponse<String> response = http.send(requestFor(history, canEdit, false),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new TqlException(COPILOT, "Model endpoint answered "
                    + response.statusCode());
        }
        JsonNode message = MAPPER.readTree(response.body())
                .path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw new TqlException(COPILOT, "Model endpoint returned no choices");
        }
        return message;
    }

    /**
     * One streaming completion ({@code stream: true}): forwards each content delta to the
     * listener as it arrives and reassembles the reply — fragmented {@code tool_calls}
     * deltas are merged by index — into the same message shape {@link #complete} returns,
     * so the tool loop, history, and trimming are unchanged.
     */
    private JsonNode completeStreaming(List<JsonNode> history, boolean canEdit,
            TurnListener listener) throws Exception {
        HttpResponse<java.util.stream.Stream<String>> response = http.send(
                requestFor(history, canEdit, true), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() >= 300) {
            throw new TqlException(COPILOT, "Model endpoint answered "
                    + response.statusCode());
        }
        StringBuilder content = new StringBuilder();
        Map<Integer, ObjectNode> calls = new java.util.TreeMap<>();
        try (java.util.stream.Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode delta = MAPPER.readTree(payload).path("choices").path(0)
                        .path("delta");
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    listener.delta(text);
                }
                for (JsonNode part : delta.path("tool_calls")) {
                    ObjectNode call = calls.computeIfAbsent(part.path("index").asInt(),
                            index -> {
                                ObjectNode fresh = MAPPER.createObjectNode();
                                fresh.put("type", "function");
                                fresh.putObject("function").put("name", "")
                                        .put("arguments", "");
                                return fresh;
                            });
                    if (part.hasNonNull("id")) {
                        call.put("id", part.path("id").asText());
                    }
                    ObjectNode function = (ObjectNode) call.path("function");
                    JsonNode partFunction = part.path("function");
                    if (partFunction.hasNonNull("name")) {
                        function.put("name", function.path("name").asText()
                                + partFunction.path("name").asText());
                    }
                    if (partFunction.hasNonNull("arguments")) {
                        function.put("arguments", function.path("arguments").asText()
                                + partFunction.path("arguments").asText());
                    }
                }
            }
        }
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        if (content.isEmpty() && calls.isEmpty()) {
            throw new TqlException(COPILOT, "Model endpoint streamed no delta");
        }
        if (content.isEmpty()) {
            message.putNull("content");
        } else {
            message.put("content", content.toString());
        }
        if (!calls.isEmpty()) {
            ArrayNode toolCalls = message.putArray("tool_calls");
            calls.values().forEach(toolCalls::add);
        }
        return message;
    }

    /** The chat-completions request; {@code stream} switches the upstream to SSE deltas. */
    private HttpRequest requestFor(List<JsonNode> history, boolean canEdit, boolean stream)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        history.forEach(messages::add);
        body.set("tools", toolDefinitions(canEdit));
        if (stream) {
            body.put("stream", true);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        String key = apiKey.get();
        if (key != null && !key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
        return request.build();
    }

    /**
     * Executes one tool call against the same gated Studio paths a human uses. The write
     * tool saves a DRAFT only — applying stays a human action in the editor — and every
     * copilot-driven save lands in the audit trail attributed to the chatting user.
     */
    String executeTool(String name, String argumentsJson, boolean canEdit, String actor) {
        try {
            JsonNode args = MAPPER.readTree(
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            return switch (name) {
                case "list_routes" -> listRoutes();
                case "read_source" -> studio.source(args.path("path").asText());
                case "lint" -> lint();
                case "schema_tables" -> schemaTables();
                case "preview_draft" -> preview(args);
                case "save_draft" -> saveDraft(args, canEdit, actor);
                default -> "Unknown tool: " + name;
            };
        } catch (RuntimeException ex) {
            return "Tool " + name + " failed: " + ex.getMessage();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return "Tool " + name + " failed: bad arguments";
        }
    }

    private String listRoutes() throws com.fasterxml.jackson.core.JsonProcessingException {
        List<Map<String, String>> routes = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", route.definition().id());
            row.put("method", route.httpMethod());
            row.put("path", route.urlPath());
            row.put("recipe", route.definition().recipe());
            row.put("source", manifest.appHome().relativize(route.source()).toString()
                    .replace('\\', '/'));
            routes.add(row);
        }
        return MAPPER.writeValueAsString(routes);
    }

    private String lint() throws com.fasterxml.jackson.core.JsonProcessingException {
        List<LintFinding> findings = new AppLinter().lint(manifest.appHome());
        return findings.isEmpty()
                ? "No findings."
                : MAPPER.writeValueAsString(findings);
    }

    private String schemaTables() throws com.fasterxml.jackson.core.JsonProcessingException {
        DocService docs = new DocService(manifest);
        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (String table : docs.tableNames()) {
            tables.put(table, docs.columnNames(table));
        }
        return tables.isEmpty()
                ? "No schema overlay captured (run the schema goal to introspect)."
                : MAPPER.writeValueAsString(tables);
    }

    private String preview(JsonNode args) {
        StudioService.PreviewResult result = studio.preview(args.path("path").asText(),
                args.path("content").asText());
        return result.valid()
                ? "VALID (" + result.kind() + "): " + result.result()
                : "INVALID (" + result.kind() + "): " + result.error();
    }

    private String saveDraft(JsonNode args, boolean canEdit, String actor) {
        if (!canEdit) {
            return "Refused: you do not hold a Studio edit role, so the copilot may not save"
                    + " drafts in this session.";
        }
        String path = args.path("path").asText();
        studio.saveDraft(path, args.path("content").asText());
        studio.recordCopilotDraft(actor, path);
        return "Draft saved at " + path + ". It is NOT served yet: review the diff and apply"
                + " it in the editor (" + "/_tesseraql/studio/ui/source?path=" + path + ").";
    }

    private ArrayNode toolDefinitions(boolean canEdit) {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(tool("list_routes", "Every route the app serves: id, method, path, recipe,"
                + " source file.", Map.of()));
        tools.add(tool("read_source", "Read one app-relative source file (route yml, 2-way"
                + " sql, template, view).", Map.of("path", "string")));
        tools.add(tool("lint", "Run the app linter; findings carry code, severity, source and"
                + " line.", Map.of()));
        tools.add(tool("schema_tables", "The introspected tables and their columns.",
                Map.of()));
        tools.add(tool("preview_draft", "Validate a buffer as it would compile (route yml,"
                + " sql, template) WITHOUT saving.",
                Map.of("path", "string", "content", "string")));
        if (canEdit) {
            tools.add(tool("save_draft", "Save a DRAFT at an app-relative path. Drafts are"
                    + " not served; a human reviews and applies in the editor.",
                    Map.of("path", "string", "content", "string")));
        }
        return tools;
    }

    private ObjectNode tool(String name, String description, Map<String, String> params) {
        ObjectNode function = MAPPER.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        ObjectNode schema = function.putObject("parameters");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        params.forEach((param, type) -> {
            properties.putObject(param).put("type", type);
            required.add(param);
        });
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    private JsonNode systemMessage() {
        return chatMessage("system", """
                You are the TesseraQL Studio copilot for the app '%s'. TesseraQL apps are \
                declarative: routes are YAML documents bound to 2-way SQL files, pages are \
                declarative view documents, and everything is gated by lint and policies. \
                Use the tools to read before you write. Propose changes by saving DRAFTS \
                (never claim anything is live): a human reviews and applies every draft in \
                the editor. Keep answers short and concrete.""".formatted(
                manifest.config().getString("tesseraql.app.name").orElse("app")));
    }

    private static JsonNode chatMessage(String role, String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static void trim(List<JsonNode> history) {
        while (history.size() > MAX_MESSAGES) {
            // Keep the system prompt; drop the oldest exchange after it.
            history.remove(1);
        }
    }

    private static List<Entry> transcript(List<JsonNode> history) {
        List<Entry> entries = new ArrayList<>();
        for (JsonNode message : history) {
            String role = message.path("role").asText();
            if ("system".equals(role) || "tool".equals(role)) {
                continue;
            }
            String text = message.path("content").isNull()
                    ? ""
                    : message.path("content").asText("");
            String tool = null;
            JsonNode calls = message.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                List<String> names = new ArrayList<>();
                calls.forEach(call -> names.add(call.path("function").path("name").asText()));
                tool = String.join(", ", names);
            }
            if (!text.isBlank() || tool != null) {
                entries.add(new Entry(role, text, tool));
            }
        }
        return entries;
    }
}
