package io.tesseraql.studio.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.runtime.SecurityConfigFactory;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Studio machinery's shared helpers, extracted verbatim from the runtime boot when the
 * workshop moved into this module (docs/studio-shell.md structural decision 3): parameter
 * parsing for the {@code studio.*} providers, the try-it console's loopback invocation, the
 * policy-engine rebind a live security edit rides, and the PDF render paths. This class only
 * relocates them — behavior is the runtime's, unchanged.
 */
final class StudioSupport {

    static final Logger LOG = LoggerFactory.getLogger(StudioSupport.class);

    /**
     * TQL-STUDIO-4234: the data-browser row edit was rejected — editor disabled, unknown
     * table, no row matches the key, or the update failed (HTTP 400).
     */
    static final io.tesseraql.core.error.TqlErrorCode ROW_EDIT_REJECTED = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.STUDIO, 4234);

    private StudioSupport() {
    }

    /** A 1-based page number from a request param (Integer or String), defaulting to 1 (I3). */
    static int parsePage(Object raw) {
        if (raw == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(raw).trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** The audit actor a Studio service provider was bound (the caller's {@code principal.loginId}). */
    static String actorOf(Map<String, Object> params) {
        Object actor = params.get("actor");
        return actor == null ? null : String.valueOf(actor);
    }

    /** The self-hosted sprite icon ids offered in the menu editor's icon picker (see icons.svg). */
    static final List<String> MENU_ICON_OPTIONS = List.of(
            "compass", "book-open", "database", "shield-check", "share-2", "blocks", "wrench",
            "database-zap", "wand-sparkles", "file-pen", "scroll-text", "activity", "users",
            "layout-dashboard", "waypoints", "arrow-left-right", "send", "panel-left");

    /**
     * Rebuilds the {@link PolicyEngine} from the app's current (re-read) config and rebinds it, so a
     * Studio policy edit written to {@code config/overlay.yml} is authorized live on the next request
     * without a restart — the auth producer looks the engine up by name per request, so the rebind
     * takes effect immediately. Only the policy engine is rebound; the authenticators are unchanged.
     */
    static void rebindPolicyEngine(RuntimeContext context, Path appHome) {
        SecurityConfig fresh = SecurityConfigFactory
                .build(new ManifestLoader().load(appHome).config());
        context.bind(TesseraqlProperties.POLICY_ENGINE_BEAN, new PolicyEngine(fresh));
    }

    /**
     * The API try-it console's loopback invocation: sends the requested method/path/body to the
     * app's own server on {@code 127.0.0.1:<port>} and returns a view model of the raw response.
     * The path must be app-relative (leading {@code /}, no {@code //} or {@code scheme://}), so the
     * call can only reach this app — never an arbitrary host.
     */
    static Map<String, Object> tryInvoke(int port, Map<String, Object> params) {
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        String method = params.get("method") == null
                ? "GET"
                : String.valueOf(params.get("method")).strip().toUpperCase(java.util.Locale.ROOT);
        if (method.isEmpty()) {
            method = "GET";
        }
        String path = str(params, "path");
        model.put("method", method);
        model.put("path", path);
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("://")) {
            model.put("error", "Enter an app path beginning with '/' (for example /api/users).");
            return model;
        }
        if (port <= 0) {
            model.put("error", "The API console needs a fixed server.port (this app binds an "
                    + "ephemeral port).");
            return model;
        }
        String query = str(params, "query");
        String url = "http://127.0.0.1:" + port + path;
        if (query != null) {
            url += (path.contains("?") ? "&" : "?") + (query.startsWith("?")
                    ? query.substring(1)
                    : query);
        }
        String body = params.get("body") == null ? null : String.valueOf(params.get("body"));
        boolean hasBody = body != null && !body.isBlank()
                && !("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method));
        String bearer = str(params, "bearer");
        String contentType = str(params, "contentType");
        // "Send my session": forward the caller's own session cookie (and its CSRF token) so the
        // loopback runs as the current Studio user — this is how browser-authenticated routes are
        // exercised. The cookie/csrf are bound from the caller's own request, used server-side only,
        // and never rendered. No escalation: the target route still enforces its own policy.
        boolean useSession = "true".equals(String.valueOf(params.get("useSession")));
        String cookie = str(params, "cookie");
        String sessionCsrf = str(params, "csrf");
        model.put("url", url);
        try {
            java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .method(method, hasBody
                            ? java.net.http.HttpRequest.BodyPublishers.ofString(body)
                            : java.net.http.HttpRequest.BodyPublishers.noBody());
            if (hasBody) {
                request.header("Content-Type",
                        contentType == null ? "application/json" : contentType);
            }
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (useSession && cookie != null) {
                request.header("Cookie", cookie);
                if (sessionCsrf != null) {
                    request.header("X-CSRF-Token", sessionCsrf);
                }
            }
            long startedNs = System.nanoTime();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            model.put("ok", true);
            model.put("status", response.statusCode());
            model.put("durationMs", (System.nanoTime() - startedNs) / 1_000_000);
            java.util.List<Map<String, Object>> headers = new java.util.ArrayList<>();
            response.headers().map().forEach((name, values) -> {
                Map<String, Object> header = new java.util.LinkedHashMap<>();
                header.put("name", name);
                header.put("value", String.join(", ", values));
                headers.add(header);
            });
            model.put("headers", headers);
            model.put("body", prettyBody(response.body()));
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            model.put("error", "Request failed: " + ex.getMessage());
        }
        return model;
    }

    /** Pretty-prints a JSON response body for the try-it console; returns it unchanged otherwise. */
    private static String prettyBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(mapper.readTree(body));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Not valid JSON after all — show the raw body.
            }
        }
        return body;
    }

    /** The number of filter condition slots the data browser exposes. */
    static final int DATA_FILTER_SLOTS = 5;
    static final int ROUTE_FORM_INPUT_SLOTS = 10;
    static final int DATA_EDIT_SLOTS = 20;

    /** Assembles the data browser's filter conditions from the indexed slot params {@code fcN/foN/fvN}. */
    static java.util.List<StudioDataService.FilterCond> dataFilters(
            Map<String, Object> params) {
        java.util.List<StudioDataService.FilterCond> filters = new java.util.ArrayList<>();
        for (int i = 0; i < DATA_FILTER_SLOTS; i++) {
            String column = str(params, "fc" + i);
            if (column == null) {
                continue;
            }
            String op = str(params, "fo" + i) == null ? "contains" : str(params, "fo" + i);
            String value = params.get("fv" + i) == null ? "" : String.valueOf(params.get("fv" + i));
            filters.add(new StudioDataService.FilterCond(column, op, value));
        }
        return filters;
    }

    /** The URL-encoded query string (table + combinator + filter slots + sort) reused by the links. */
    static String dataQueryBase(String datasource, String table, String combinator,
            String sortColumn, String sortDir, java.util.List<Map<String, Object>> filterRows) {
        StringBuilder query = new StringBuilder("ds=").append(urlEncode(datasource))
                .append("&table=").append(urlEncode(table))
                .append("&combinator=").append(urlEncode(combinator));
        for (int i = 0; i < filterRows.size(); i++) {
            Map<String, Object> row = filterRows.get(i);
            query.append("&fc").append(i).append('=')
                    .append(urlEncode(String.valueOf(row.get("column"))))
                    .append("&fo").append(i).append('=')
                    .append(urlEncode(String.valueOf(row.get("op"))))
                    .append("&fv").append(i).append('=')
                    .append(urlEncode(String.valueOf(row.get("value"))));
        }
        return query.append("&sort=").append(urlEncode(sortColumn == null ? "" : sortColumn))
                .append("&dir=").append(sortDir).toString();
    }

    static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Rejects a Track J2 mutation posted without the explicit confirm acknowledgment. */
    static void requireExplicitConfirm(Map<String, Object> params, String what) {
        if (!"true".equals(String.valueOf(params.get("confirm")))) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4232),
                    what + " need explicit confirmation");
        }
    }

    /**
     * The configured copilot endpoint, gated by the same deny-by-default egress allow-list an
     * {@code httpCall} step obeys (docs/copilot.md): every turn ships app source to this
     * endpoint, so a host outside {@code tesseraql.http.outbound.allowedHosts} fails the boot
     * with {@code TQL-SEC-4085} — a chat must never become the one outbound call the egress
     * policy does not govern.
     */
    static String copilotEndpoint(io.tesseraql.yaml.config.AppConfig config,
            io.tesseraql.yaml.http.HttpOutbound outbound) {
        String endpoint = config.requireString("tesseraql.copilot.endpoint");
        String host;
        try {
            host = java.net.URI.create(endpoint).getHost();
        } catch (IllegalArgumentException ex) {
            host = null;
        }
        if (host == null) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.SEC, 4085),
                    "tesseraql.copilot.endpoint '" + endpoint
                            + "' must be an absolute http or https URL");
        }
        if (!outbound.isHostAllowed(host)) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.SEC, 4085),
                    "Copilot endpoint host '" + host
                            + "' is not in tesseraql.http.outbound.allowedHosts (egress is"
                            + " deny by default); allow it:\n"
                            + "tesseraql:\n"
                            + "  http:\n"
                            + "    outbound:\n"
                            + "      allowedHosts:\n"
                            + "        - " + host);
        }
        return endpoint;
    }

    /** Rejects a copilot call when the operator has not configured the panel. */
    static void requireCopilot(io.tesseraql.studio.CopilotService copilot) {
        if (copilot == null) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4235),
                    "The copilot is not configured (tesseraql.copilot.enabled/endpoint/"
                            + "model)");
        }
    }

    static String requiredParam(Map<String, Object> params, String key) {
        String value = str(params, key);
        if (value == null || value.isBlank()) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4231),
                    "Missing required field: " + key);
        }
        return value.trim();
    }

    static void putIfPresent(Map<String, Object> values, String dottedKey,
            Map<String, Object> params, String key) {
        String value = str(params, key);
        if (value != null && !value.isBlank()) {
            values.put(dottedKey, value.trim());
        }
    }

    static Map<String, String> parseQueryString(String query) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq),
                    java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJsonObject(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
                    Map.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        } catch (java.io.IOException ex) {
            return Map.of();
        }
    }

    /** The k0/v0..k2/v2 primary-key slots of a data-browser row-edit request (Track J4). */
    static Map<String, String> dataRowKey(Map<String, Object> params) {
        Map<String, String> key = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String column = str(params, "k" + i);
            String value = str(params, "v" + i);
            if (column != null && value != null) {
                key.put(column, value);
            }
        }
        return key;
    }

    /** A request parameter as a trimmed string, or null when absent or blank. */
    static String str(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * A menu-item index parameter, refused when it is not a number.
     *
     * <p>It used to fall back to {@code -1}, which the service treated as an out-of-range no-op
     * while the handler still answered {@code {"removed": true}} — a change reported that never
     * happened (docs/silent-tolerance.md O10). The page-number reader below keeps its clamp:
     * there, falling back to the first page is the documented behaviour, not a lost edit.
     */
    static int menuIndex(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException ex) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4241),
                    "Menu index '" + value + "' is not a number");
        }
    }

    /** Parses a page-number parameter, yielding -1 (clamped to the first page) when malformed. */
    static int parseIndex(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** A human-readable visibility summary for a menu item's editor row. */
    static String menuVisibility(io.tesseraql.yaml.menu.MenuSpec.MenuItem item) {
        if (item.roles().isEmpty() && item.permissions().isEmpty()) {
            return "Public";
        }
        StringBuilder summary = new StringBuilder();
        if (!item.roles().isEmpty()) {
            summary.append("Roles: ").append(String.join(", ", item.roles()));
        }
        if (!item.permissions().isEmpty()) {
            if (summary.length() > 0) {
                summary.append("; ");
            }
            summary.append("Permissions: ").append(String.join(", ", item.permissions()));
        }
        return summary.toString();
    }

    /**
     * The sample principal for a Studio JSON render's field masking (backlog A1 follow-up): built from
     * the render context's {@code principal} map ({@code roles}/{@code permissions}/…), or {@code null}
     * (an anonymous viewer) when the sample carries none.
     */
    @SuppressWarnings("unchecked")
    static io.tesseraql.security.Principal samplePrincipal(Map<String, Object> context) {
        if (!(context.get("principal") instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.function.Function<String, String> str = key -> map.get(key) == null
                ? null
                : String.valueOf(map.get(key));
        java.util.function.Function<String, List<String>> list = key -> map
                .get(key) instanceof List<?> values
                        ? values.stream().map(String::valueOf).toList()
                        : List.of();
        Map<String, Object> claims = map.get("claims") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
        return new io.tesseraql.security.Principal(str.apply("subject"), str.apply("loginId"),
                str.apply("displayName"), str.apply("tenantId"), list.apply("groups"),
                list.apply("roles"), list.apply("permissions"), claims);
    }

    /**
     * Renders a {@code query-export} {@code format: pdf} route's PDF for the Studio preview (backlog
     * A1 follow-up) through the canonical PDF codec, or {@code null} when no {@code pdf} codec is on
     * the classpath (the optional {@code tesseraql-pdf} module is absent).
     */
    static byte[] renderExportPdf(io.tesseraql.yaml.model.ExportSpec export,
            Path routeDir, Path appHome, List<Map<String, Object>> rows,
            ClassLoader modulesLoader) {
        io.tesseraql.core.files.FileCodec codec;
        try {
            codec = io.tesseraql.core.files.FileCodecs.discover(modulesLoader).require("pdf");
        } catch (io.tesseraql.core.error.TqlException ex) {
            return null;
        }
        Path template = export.template() == null || export.template().isBlank()
                ? null
                : routeDir.resolve(export.template());
        io.tesseraql.core.files.FileWriteSpec spec = export.toWriteSpec(template, appHome);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            codec.write(out, spec, io.tesseraql.core.files.ExportModel.repeatable(rows,
                    java.util.Map.of()));
        } catch (Exception ex) {
            throw new IllegalStateException("PDF render failed: " + ex.getMessage(), ex);
        }
        return out.toByteArray();
    }

    /**
     * Renders the documentation portal's route catalog (one row per route) to a PDF table through
     * the canonical PDF codec's built-in grid (no template), reusing the same {@code FileCodecs}
     * discovery the export routes use. Returns {@code null} when the optional {@code tesseraql-pdf}
     * module is absent so the portal degrades to a clear note rather than failing (F8, slice 2).
     */
    static byte[] renderRoutesPdf(List<Map<String, Object>> rows, Path appHome,
            ClassLoader modulesLoader) {
        io.tesseraql.core.files.FileCodec codec;
        try {
            codec = io.tesseraql.core.files.FileCodecs.discover(modulesLoader).require("pdf");
        } catch (io.tesseraql.core.error.TqlException ex) {
            return null;
        }
        List<io.tesseraql.core.files.ColumnMapping> columns = List.of(
                new io.tesseraql.core.files.ColumnMapping("id", "Id", null),
                new io.tesseraql.core.files.ColumnMapping("method", "Method", null),
                new io.tesseraql.core.files.ColumnMapping("path", "Path", null),
                new io.tesseraql.core.files.ColumnMapping("recipe", "Recipe", null),
                new io.tesseraql.core.files.ColumnMapping("tests", "Tests", null));
        io.tesseraql.core.files.FileWriteSpec spec = new io.tesseraql.core.files.FileWriteSpec(
                columns, null, null, null, appHome, null, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            codec.write(out, spec, io.tesseraql.core.files.ExportModel.repeatable(rows,
                    java.util.Map.of()));
        } catch (Exception ex) {
            throw new IllegalStateException("Routes PDF render failed: " + ex.getMessage(), ex);
        }
        return out.toByteArray();
    }
}
