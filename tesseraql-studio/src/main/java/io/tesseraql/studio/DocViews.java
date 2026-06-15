package io.tesseraql.studio;

import io.tesseraql.studio.DocService.DocSpec;
import io.tesseraql.studio.DocService.RouteEntry;
import io.tesseraql.studio.DocService.TestRef;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecModel;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template-ready views over the {@link DocService} (documentation portal v1): pure mappings from the
 * spec records to plain maps and lists, with detail links pre-encoded, served to the bundled studio
 * app through the {@code docs.*} service providers. Mirrors {@link StudioViews}; the templates place
 * these facts but do not author them.
 */
public final class DocViews {

    private static final String ROUTE_URL = "/_tesseraql/studio/ui/docs/route?id=";

    private DocViews() {
    }

    /** The docs index model: the app name, the route summaries, and the migration listing. */
    public static Map<String, Object> index(String appName, DocSpec spec) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", appName);
        List<Map<String, Object>> routes = new ArrayList<>();
        for (RouteEntry entry : spec.routes()) {
            RouteSpec route = entry.route();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.id());
            row.put("method", route.method());
            row.put("path", route.path());
            row.put("recipe", route.recipe());
            row.put("testCount", entry.tests().size());
            row.put("detailUrl", routeUrl(route.id()));
            routes.add(row);
        }
        model.put("routes", routes);
        model.put("hasRoutes", !routes.isEmpty());
        List<Map<String, Object>> migrations = new ArrayList<>();
        for (RouteSpecModel.Migration migration : spec.migrations()) {
            migrations.add(migrationRow(migration));
        }
        model.put("migrations", migrations);
        model.put("hasMigrations", !migrations.isEmpty());
        return model;
    }

    /** The full per-route reference model. */
    public static Map<String, Object> route(RouteEntry entry) {
        RouteSpec route = entry.route();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", route.id());
        model.put("method", route.method());
        model.put("path", route.path());
        model.put("recipe", route.recipe());
        model.put("kind", route.kind());
        model.put("inputs", inputs(route.inputs()));
        model.put("hasInputs", !route.inputs().isEmpty());
        model.put("security", security(route.security()));
        model.put("validations", validations(route.validations()));
        model.put("hasValidations", !route.validations().isEmpty());
        model.put("notifications", notifications(route.notifications()));
        model.put("hasNotifications", !route.notifications().isEmpty());
        model.put("response", response(route.response()));
        model.put("sql", statements(route.sql()));
        model.put("hasSql", !route.sql().isEmpty());
        model.put("tests", tests(entry.tests()));
        model.put("hasTests", !entry.tests().isEmpty());
        return model;
    }

    /** The Markdown doc-body model: the doc path and its pre-rendered, CSP-safe HTML. */
    public static Map<String, Object> doc(String path, String html) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("path", path);
        model.put("html", html);
        return model;
    }

    /** The live-search results fragment model: the query echo and the ranked route hits. */
    public static Map<String, Object> searchResults(String query, List<DocService.Hit> hits) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("query", query == null ? "" : query);
        List<Map<String, Object>> results = new ArrayList<>();
        for (DocService.Hit hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", hit.id());
            row.put("method", hit.method());
            row.put("path", hit.path());
            row.put("detailUrl", routeUrl(hit.id()));
            results.add(row);
        }
        model.put("results", results);
        model.put("hasResults", !results.isEmpty());
        return model;
    }

    private static List<Map<String, Object>> inputs(List<RouteSpec.Input> inputs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteSpec.Input input : inputs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", input.name());
            row.put("type", input.type());
            row.put("required", input.required());
            row.put("constraints", constraints(input));
            rows.add(row);
        }
        return rows;
    }

    /** Human-readable constraint chips for an input (only the constraints it declares). */
    private static List<String> constraints(RouteSpec.Input input) {
        List<String> chips = new ArrayList<>();
        if (input.defaultValue() != null) {
            chips.add("default " + input.defaultValue());
        }
        if (input.min() != null) {
            chips.add("min " + input.min());
        }
        if (input.max() != null) {
            chips.add("max " + input.max());
        }
        if (input.maxLength() != null) {
            chips.add("maxLength " + input.maxLength());
        }
        if (input.enumValues() != null && !input.enumValues().isEmpty()) {
            chips.add("enum " + String.join("|", input.enumValues()));
        }
        if (input.format() != null) {
            chips.add("format " + input.format());
        }
        return chips;
    }

    private static Map<String, Object> security(RouteSpec.Security security) {
        if (security == null) {
            return null;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("auth", security.auth());
        model.put("policy", security.policy());
        model.put("provider", security.provider());
        model.put("csrf", security.csrf());
        return model;
    }

    private static List<Map<String, Object>> validations(List<RouteSpec.Validation> validations) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteSpec.Validation validation : validations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", validation.id());
            row.put("kind", validation.kind());
            row.put("expression", validation.expression());
            row.put("file", validation.file());
            row.put("field", validation.field());
            row.put("when", validation.when());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> notifications(
            List<RouteSpec.Notification> notifications) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteSpec.Notification notification : notifications) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", notification.id());
            row.put("channel", notification.channel());
            row.put("when", notification.when());
            row.put("payload", notification.payload());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> response(RouteSpec.Response response) {
        if (response == null) {
            return null;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("kind", response.kind());
        model.put("status", response.status());
        model.put("template", response.template());
        model.put("contentType", response.contentType());
        model.put("location", response.location());
        return model;
    }

    private static List<Map<String, Object>> statements(List<RouteSpec.SqlStatement> statements) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteSpec.SqlStatement statement : statements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", statement.label());
            row.put("file", statement.file());
            row.put("contract", statement.contract());
            row.put("service", statement.service());
            row.put("mode", statement.mode());
            row.put("statement", statement.statement());
            row.put("binds", statement.binds());
            List<Map<String, Object>> structure = new ArrayList<>();
            for (RouteSpec.Control control : statement.structure()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("kind", control.kind());
                node.put("expression", control.expression());
                node.put("depth", control.depth());
                structure.add(node);
            }
            row.put("structure", structure);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> tests(List<TestRef> tests) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TestRef test : tests) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", test.name());
            row.put("kind", test.kind());
            row.put("target", test.target());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> migrationRow(RouteSpecModel.Migration migration) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("datasource", migration.datasource());
        row.put("vendor", migration.vendor());
        row.put("version", migration.version());
        row.put("description", migration.description());
        row.put("path", migration.path());
        return row;
    }

    private static String routeUrl(String id) {
        return ROUTE_URL + URLEncoder.encode(id == null ? "" : id, StandardCharsets.UTF_8);
    }
}
