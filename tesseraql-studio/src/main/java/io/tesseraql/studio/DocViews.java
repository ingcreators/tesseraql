package io.tesseraql.studio;

import io.tesseraql.core.sql.SqlTableReferences;
import io.tesseraql.studio.DocService.DocSpec;
import io.tesseraql.studio.DocService.RouteEntry;
import io.tesseraql.studio.DocService.TestRef;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecModel;
import io.tesseraql.yaml.openapi.OpenApiDiff;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Template-ready views over the {@link DocService} (documentation portal v1): pure mappings from the
 * spec records to plain maps and lists, with detail links pre-encoded, served to the bundled studio
 * app through the {@code docs.*} service providers. Mirrors {@link StudioViews}; the templates place
 * these facts but do not author them.
 */
public final class DocViews {

    private static final String ROUTE_URL = "/_tesseraql/studio/ui/docs/route?id=";
    private static final String TABLE_URL = "/_tesseraql/studio/ui/docs/schema/table?";

    private DocViews() {
    }

    /**
     * The docs index model: the app name, the route summaries, the migration listing, and — when a
     * run overlay is present — a coverage summary strip plus per-route status (covered, pass/fail,
     * line coverage). The overlay is optional; columns degrade to absent when it is {@code null}.
     */
    public static Map<String, Object> index(String appName, DocSpec spec, ReportOverlay overlay) {
        return index(appName, spec, overlay, null, null);
    }

    /** The sortable columns of the route catalog, in their header order (platform-UX I2). */
    private static final List<String> ROUTE_SORT_COLS = List.of("id", "method", "path", "recipe",
            "testCount", "linePct");

    /**
     * The docs index sorted by a route-catalog column (platform-UX I2): {@code sort} names one of
     * {@link #ROUTE_SORT_COLS} (default {@code id}) and {@code dir} is {@code asc}/{@code desc}. The
     * model carries the per-column header link and {@code aria-sort} the hc-datagrid renders from.
     */
    public static Map<String, Object> index(String appName, DocSpec spec, ReportOverlay overlay,
            String sort, String dir) {
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
            applyRouteOverlay(row, overlay == null ? null : overlay.routeReport(route.id()));
            routes.add(row);
        }
        sortRoutes(routes, sort, dir, model);
        model.put("routes", routes);
        model.put("hasRoutes", !routes.isEmpty());
        model.put("hasReport", overlay != null);
        model.put("report", reportSummary(overlay));
        List<Map<String, Object>> migrations = new ArrayList<>();
        for (RouteSpecModel.Migration migration : spec.migrations()) {
            migrations.add(migrationRow(migration));
        }
        model.put("migrations", migrations);
        model.put("hasMigrations", !migrations.isEmpty());
        return model;
    }

    /** Sorts the route rows by the chosen column and records the sort state for the header links. */
    private static void sortRoutes(List<Map<String, Object>> routes, String sort, String dir,
            Map<String, Object> model) {
        String key = sort != null && ROUTE_SORT_COLS.contains(sort) ? sort : "id";
        boolean desc = "desc".equalsIgnoreCase(dir);
        Comparator<Map<String, Object>> cmp = routeComparator(key);
        routes.sort(desc ? cmp.reversed() : cmp);
        putSortLinks(ROUTE_SORT_COLS, key, desc, "/_tesseraql/studio/ui/docs", model);
    }

    /**
     * Records the sort state for an hc-datagrid: per sortable column, the header link
     * ({@code ?sort=col&dir=<flip>}) and the {@code aria-sort} the kit renders the arrow from. The
     * active column flips its direction on click; any other column starts ascending (platform-UX I2).
     */
    private static void putSortLinks(List<String> cols, String key, boolean desc, String baseUrl,
            Map<String, Object> model) {
        model.put("sortKey", key);
        model.put("sortDir", desc ? "desc" : "asc");
        Map<String, String> sortHref = new LinkedHashMap<>();
        Map<String, String> ariaSort = new LinkedHashMap<>();
        for (String col : cols) {
            boolean active = col.equals(key);
            String next = active && !desc ? "desc" : "asc";
            sortHref.put(col, baseUrl + "?sort=" + col + "&dir=" + next);
            ariaSort.put(col, active ? (desc ? "descending" : "ascending") : "none");
        }
        model.put("sortHref", sortHref);
        model.put("ariaSort", ariaSort);
    }

    private static Comparator<Map<String, Object>> routeComparator(String key) {
        return switch (key) {
            case "testCount" -> Comparator.comparingInt(
                    r -> ((Number) r.getOrDefault("testCount", 0)).intValue());
            case "linePct" -> Comparator.comparing(
                    r -> (Integer) r.get("linePct"),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            // id / method / path / recipe: case-insensitive text.
            default -> Comparator.comparing(
                    r -> String.valueOf(r.getOrDefault(key, "")).toLowerCase(Locale.ROOT));
        };
    }

    /** The full per-route reference model without data-dependency links (no schema overlay). */
    public static Map<String, Object> route(RouteEntry entry, ReportOverlay.RouteReport report) {
        return route(entry, report, Map.of());
    }

    /**
     * The full per-route reference model. When {@code report} is non-null (a run overlay is present
     * for this route) it merges the run facts: a status badge row (covered, pass/fail, line/branch
     * coverage), each test's pass/fail, and each SQL statement's coverage. {@code tableLinks} maps a
     * lowercased table name to its schema table page, so the route's inferred data dependencies (the
     * tables its SQL reads and writes) cross-link to the schema portal when introspected.
     */
    public static Map<String, Object> route(RouteEntry entry, ReportOverlay.RouteReport report,
            Map<String, String> tableLinks) {
        RouteSpec route = entry.route();
        Map<String, ReportOverlay.CaseResult> resultsByName = resultsByName(report);
        Map<String, ReportOverlay.SqlFileCoverage> coverageByName = coverageByName(report);
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
        model.put("sql", statements(route.sql(), coverageByName));
        model.put("hasSql", !route.sql().isEmpty());
        List<Map<String, Object>> reads = tableDependencies(route.sql(),
                SqlTableReferences.Access.READ, tableLinks);
        List<Map<String, Object>> writes = tableDependencies(route.sql(),
                SqlTableReferences.Access.WRITE, tableLinks);
        model.put("reads", reads);
        model.put("writes", writes);
        model.put("hasDataDeps", !reads.isEmpty() || !writes.isEmpty());
        model.put("tests", tests(entry.tests(), resultsByName));
        model.put("hasTests", !entry.tests().isEmpty());
        applyRouteSummary(model, report);
        return model;
    }

    /**
     * The public shared-link view model (documentation portal F8, slice 3): a deliberately reduced,
     * read-only projection of a route's <em>contract</em> — its surface (method/path/recipe),
     * inputs, security summary, validations, notifications, and response shape. It omits the bound
     * SQL, the test cross-references, and any run/coverage overlay, so an unauthenticated viewer of
     * a signed link sees the interface but not the implementation internals. {@code shared} marks the
     * page as the public view (no portal chrome).
     */
    public static Map<String, Object> share(RouteEntry entry) {
        RouteSpec route = entry.route();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("shared", true);
        model.put("shareInvalid", false);
        model.put("id", route.id());
        model.put("method", route.method());
        model.put("path", route.path());
        model.put("recipe", route.recipe());
        model.put("kind", route.kind());
        model.put("security", security(route.security()));
        model.put("inputs", inputs(route.inputs()));
        model.put("hasInputs", !route.inputs().isEmpty());
        model.put("validations", validations(route.validations()));
        model.put("hasValidations", !route.validations().isEmpty());
        model.put("notifications", notifications(route.notifications()));
        model.put("hasNotifications", !route.notifications().isEmpty());
        model.put("response", response(route.response()));
        return model;
    }

    /**
     * The public shared-link view of a schema table (F8 slice 3, extended): the same read-only
     * column/key/index reference as {@link #table}, marked as the shared view. The foreign-key
     * targets are rendered as plain text by the public template (their detail links would be
     * bearer-gated), so no privileged navigation is offered.
     */
    public static Map<String, Object> shareTable(String datasource, CatalogSchema.Table table) {
        Map<String, Object> model = table(datasource, table);
        model.put("shared", true);
        model.put("shareInvalid", false);
        return model;
    }

    /**
     * The public shared-link view of the coverage dashboard (F8 slice 3, extended): the run summary,
     * gate verdict, item-coverage standing, and trend — but <em>not</em> the per-test failure list,
     * which is withheld from an unauthenticated viewer.
     */
    public static Map<String, Object> shareCoverage(String appName, ReportOverlay overlay,
            List<DocService.HistoryPoint> history) {
        Map<String, Object> model = coverage(appName, overlay, history);
        model.put("shared", true);
        model.put("shareInvalid", false);
        // Withhold per-test failure detail (names/messages) from the public view.
        model.remove("failingCases");
        model.put("hasFailingCases", false);
        return model;
    }

    /** Number of uncovered item ids listed per kind before collapsing to a "+N more" hint. */
    private static final int UNCOVERED_LIMIT = 10;

    /**
     * The coverage dashboard model (documentation portal v2): the run summary, the gate verdict and
     * its violations, each item-coverage kind's standing with a capped uncovered list, and every
     * failing test case across the app. Empty (just the app name) when no run overlay is present.
     */
    public static Map<String, Object> coverage(String appName, ReportOverlay overlay,
            List<DocService.HistoryPoint> history) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", appName);
        model.put("hasReport", overlay != null);
        if (overlay == null) {
            return model;
        }
        model.put("report", reportSummary(overlay));
        model.put("trend", trend(history));
        List<String> gateFailures = overlay.gate() == null ? List.of() : overlay.gate().failures();
        model.put("gateFailures", gateFailures);
        model.put("hasGateFailures", !gateFailures.isEmpty());

        List<Map<String, Object>> kinds = new ArrayList<>();
        for (ReportOverlay.KindCoverage kind : overlay.kinds()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind.kind());
            row.put("covered", kind.covered());
            row.put("declared", kind.declared());
            row.put("pct", pct(kind.ratio()));
            row.put("full", kind.covered() >= kind.declared());
            List<String> uncovered = kind.uncovered();
            row.put("uncovered", uncovered.size() > UNCOVERED_LIMIT
                    ? uncovered.subList(0, UNCOVERED_LIMIT)
                    : uncovered);
            row.put("uncoveredMore", Math.max(0, uncovered.size() - UNCOVERED_LIMIT));
            kinds.add(row);
        }
        model.put("kinds", kinds);
        model.put("hasKinds", !kinds.isEmpty());

        List<Map<String, Object>> failing = new ArrayList<>();
        for (Map.Entry<String, ReportOverlay.RouteReport> entry : overlay.routes().entrySet()) {
            for (ReportOverlay.CaseResult test : entry.getValue().tests()) {
                if (!test.passed()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("route", entry.getKey());
                    row.put("name", test.name());
                    row.put("message", test.message());
                    failing.add(row);
                }
            }
        }
        model.put("failingCases", failing);
        model.put("hasFailingCases", !failing.isEmpty());
        return model;
    }

    /**
     * The schema index model (documentation portal v3): per datasource, the tables and views it
     * holds with their column counts, primary key, and detail links. Empty (just the app name) when
     * no schema overlay is present.
     */
    public static Map<String, Object> schema(String appName, SchemaOverlay overlay) {
        return schema(appName, overlay, null, null);
    }

    /** The sortable columns of each datasource's table list, in header order (platform-UX I2). */
    private static final List<String> SCHEMA_SORT_COLS = List.of("name", "type", "columnCount",
            "foreignKeyCount");

    /**
     * The schema index sorted by a table-list column (platform-UX I2): {@code sort} names one of
     * {@link #SCHEMA_SORT_COLS} (default {@code name}) and {@code dir} is {@code asc}/{@code desc}; the
     * same sort applies to every datasource's table list, and the model carries the header link and
     * {@code aria-sort} the hc-datagrid renders from.
     */
    public static Map<String, Object> schema(String appName, SchemaOverlay overlay, String sort,
            String dir) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", appName);
        boolean has = overlay != null && !overlay.datasources().isEmpty();
        model.put("hasSchema", has);
        if (!has) {
            return model;
        }
        String key = sort != null && SCHEMA_SORT_COLS.contains(sort) ? sort : "name";
        boolean desc = "desc".equalsIgnoreCase(dir);
        Comparator<Map<String, Object>> cmp = schemaComparator(key);
        List<Map<String, Object>> datasources = new ArrayList<>();
        for (Map.Entry<String, CatalogSchema> entry : overlay.datasources().entrySet()) {
            Map<String, Object> ds = new LinkedHashMap<>();
            ds.put("name", entry.getKey());
            List<Map<String, Object>> tables = new ArrayList<>();
            for (CatalogSchema.Table table : entry.getValue().tables()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", table.name());
                row.put("type", table.type());
                row.put("columnCount", table.columns().size());
                row.put("primaryKey", String.join(", ", table.primaryKey()));
                row.put("foreignKeyCount", table.foreignKeys().size());
                row.put("detailUrl", tableUrl(entry.getKey(), table.name()));
                tables.add(row);
            }
            tables.sort(desc ? cmp.reversed() : cmp);
            ds.put("tables", tables);
            ds.put("tableCount", tables.size());
            datasources.add(ds);
        }
        model.put("datasources", datasources);
        putSortLinks(SCHEMA_SORT_COLS, key, desc, "/_tesseraql/studio/ui/docs/schema", model);
        return model;
    }

    private static Comparator<Map<String, Object>> schemaComparator(String key) {
        return switch (key) {
            case "columnCount", "foreignKeyCount" -> Comparator.comparingInt(
                    r -> ((Number) r.getOrDefault(key, 0)).intValue());
            // name / type: case-insensitive text.
            default -> Comparator.comparing(
                    r -> String.valueOf(r.getOrDefault(key, "")).toLowerCase(Locale.ROOT));
        };
    }

    /** The per-table reference model without route-usage cross-links (used by the public share view). */
    public static Map<String, Object> table(String datasource, CatalogSchema.Table table) {
        return table(datasource, table, DocService.RouteUsage.EMPTY);
    }

    /**
     * The per-table reference model (documentation portal v3): the columns (readable type,
     * nullability, default, primary-key/auto-increment flags), the primary key, the foreign keys
     * (each linked to its referenced table page), and the unique indexes. {@code usage} adds the
     * reverse data-dependency cross-links — the routes whose SQL reads from and writes to this table
     * (Studio backlog: the SQL&rarr;table dependency graph) — each linking back to its route page.
     */
    public static Map<String, Object> table(String datasource, CatalogSchema.Table table,
            DocService.RouteUsage usage) {
        Set<String> pk = new HashSet<>(table.primaryKey());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("datasource", datasource);
        model.put("name", table.name());
        model.put("type", table.type());
        model.put("schema", table.schema());
        model.put("primaryKey", String.join(", ", table.primaryKey()));
        model.put("hasPrimaryKey", !table.primaryKey().isEmpty());

        List<Map<String, Object>> columns = new ArrayList<>();
        for (CatalogSchema.Column column : table.columns()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", column.name());
            row.put("type", columnType(column));
            row.put("nullable", column.nullable());
            row.put("autoincrement", column.autoincrement());
            row.put("defaultValue", column.defaultValue());
            row.put("primaryKey", pk.contains(column.name()));
            columns.add(row);
        }
        model.put("columns", columns);

        List<Map<String, Object>> foreignKeys = new ArrayList<>();
        for (CatalogSchema.ForeignKey fk : table.foreignKeys()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", fk.name());
            row.put("columns", String.join(", ", fk.columns()));
            row.put("refTable", fk.refTable());
            row.put("refColumns", String.join(", ", fk.refColumns()));
            row.put("refUrl", tableUrl(datasource, fk.refTable()));
            foreignKeys.add(row);
        }
        model.put("foreignKeys", foreignKeys);
        model.put("hasForeignKeys", !foreignKeys.isEmpty());

        List<Map<String, Object>> indexes = new ArrayList<>();
        for (CatalogSchema.Index index : table.uniqueIndexes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", index.name());
            row.put("columns", String.join(", ", index.columns()));
            indexes.add(row);
        }
        model.put("uniqueIndexes", indexes);
        model.put("hasUniqueIndexes", !indexes.isEmpty());

        model.put("readByRoutes", routeRefs(usage.readers()));
        model.put("writeByRoutes", routeRefs(usage.writers()));
        model.put("hasRouteUsage", !usage.isEmpty());
        return model;
    }

    /** Route-reference rows (id, method, detail link) for a table's reverse data dependencies. */
    private static List<Map<String, Object>> routeRefs(List<DocService.RouteRef> refs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocService.RouteRef ref : refs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ref.id());
            row.put("method", ref.method());
            row.put("url", ref.url());
            rows.add(row);
        }
        return rows;
    }

    /** A readable column type: the SQL type name, with a length appended for character types. */
    private static String columnType(CatalogSchema.Column column) {
        if (column.size() > 0 && isCharacter(column.jdbcType())) {
            return column.sqlTypeName() + "(" + column.size() + ")";
        }
        return column.sqlTypeName();
    }

    private static boolean isCharacter(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> true;
            default -> false;
        };
    }

    private static String tableUrl(String datasource, String name) {
        return TABLE_URL + "ds="
                + URLEncoder.encode(datasource == null ? "" : datasource, StandardCharsets.UTF_8)
                + "&name=" + URLEncoder.encode(name == null ? "" : name, StandardCharsets.UTF_8);
    }

    /**
     * The export page model (documentation portal F8): the app name and the downloadable spec
     * artifacts the portal serves live from the manifest — the OpenAPI 3 document and the htmx
     * interaction contract. Each artifact carries a label, a short description, its download
     * filename, and the bearer-gated endpoint URL (so it can be copied and shared with API tooling).
     */
    public static Map<String, Object> export(String appName) {
        return export(appName, null);
    }

    /**
     * The export page model (documentation portal F8) with the API changelog (Studio backlog: API
     * spec diff). When {@code changelog} is non-null (an OpenAPI baseline sidecar is present) it adds
     * the operations added, removed, or changed since that baseline — each non-removed entry linking
     * to its route page — so the page shows both the current spec and what changed since a release.
     */
    public static Map<String, Object> export(String appName, OpenApiDiff.ApiChangelog changelog) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", appName);
        List<Map<String, Object>> artifacts = new ArrayList<>();
        artifacts.add(artifact("OpenAPI 3", "openapi.json",
                "/_tesseraql/studio/ui/docs/export/openapi",
                "The REST contract for every route: paths, parameters, request and response bodies, "
                        + "and security schemes. Feed it to Swagger UI, Postman, or a client "
                        + "generator."));
        artifacts.add(artifact("htmx contract", "htmx-contract.json",
                "/_tesseraql/studio/ui/docs/export/htmx",
                "The htmx interaction contract: each fragment route's trigger, target, and swap, for "
                        + "documenting the hypermedia surface."));
        model.put("artifacts", artifacts);
        applyApiChangelog(model, changelog);
        return model;
    }

    /**
     * Adds the API-changelog facts (baseline presence, counts, per-operation rows) to a docs
     * view model — the export and release-diff pages share it.
     */
    public static void applyApiChangelog(Map<String, Object> model,
            OpenApiDiff.ApiChangelog changelog) {
        model.put("hasBaseline", changelog != null);
        model.put("baselinePath", DocService.OPENAPI_BASELINE_PATH);
        if (changelog == null) {
            return;
        }
        model.put("hasChanges", !changelog.isEmpty());
        model.put("addedCount", changelog.count(OpenApiDiff.ApiChangelog.Kind.ADDED));
        model.put("removedCount", changelog.count(OpenApiDiff.ApiChangelog.Kind.REMOVED));
        model.put("changedCount", changelog.count(OpenApiDiff.ApiChangelog.Kind.CHANGED));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OpenApiDiff.ApiChangelog.Entry entry : changelog.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            String kind = entry.kind().name().toLowerCase(Locale.ROOT);
            row.put("kind", kind);
            row.put("method", entry.method());
            row.put("path", entry.path());
            row.put("operationId", entry.operationId());
            // A removed operation has no route page to link to; added/changed link to theirs.
            row.put("url", entry.kind() == OpenApiDiff.ApiChangelog.Kind.REMOVED
                    || entry.operationId() == null ? null : routeUrl(entry.operationId()));
            row.put("details", entry.details());
            rows.add(row);
        }
        model.put("changes", rows);
    }

    /**
     * The printable route-catalog model (documentation portal F8): the app name and, when the
     * optional PDF codec is on the classpath, the rendered catalog as a {@code data:} URL for an
     * inline preview and a download link. {@code hasPdf} is false when the {@code tesseraql-pdf}
     * module is absent, so the page degrades to a clear note instead of a broken frame.
     */
    public static Map<String, Object> routesPdf(String appName, String pdfDataUrl) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", appName);
        model.put("hasPdf", pdfDataUrl != null);
        if (pdfDataUrl != null) {
            model.put("pdfUrl", pdfDataUrl);
        }
        return model;
    }

    private static Map<String, Object> artifact(String label, String filename, String url,
            String description) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("filename", filename);
        row.put("url", url);
        row.put("description", description);
        return row;
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
            row.put("detailUrl", hit.url());
            results.add(row);
        }
        model.put("results", results);
        model.put("hasResults", !results.isEmpty());
        model.put("count", results.size());
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
    static List<String> constraints(RouteSpec.Input input) {
        List<String> lead = domainChip(input);
        if (!lead.isEmpty()) {
            List<String> chips = new ArrayList<>(lead);
            chips.addAll(plainConstraints(input));
            return chips;
        }
        return plainConstraints(input);
    }

    /** The field-domain chip (docs/field-domains.md), leading so shared identity reads first. */
    private static List<String> domainChip(RouteSpec.Input input) {
        return input.domain() == null ? List.of() : List.of("domain " + input.domain());
    }

    private static List<String> plainConstraints(RouteSpec.Input input) {
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

    private static List<Map<String, Object>> statements(List<RouteSpec.SqlStatement> statements,
            Map<String, ReportOverlay.SqlFileCoverage> coverageByName) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteSpec.SqlStatement statement : statements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", statement.label());
            row.put("file", statement.file());
            row.put("contract", statement.contract());
            row.put("service", statement.service());
            row.put("mode", statement.mode());
            row.put("statement", statement.statement());
            row.put("statementHtml", statement.statement() == null
                    ? null
                    : SqlHighlighter.highlight(statement.statement()));
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
            ReportOverlay.SqlFileCoverage coverage = coverageByName.get(basename(statement.file()));
            if (coverage != null) {
                row.put("linePct", pct(coverage.lineRatio()));
                row.put("branchPct",
                        coverage.branchCount() == 0 ? null : pct(coverage.branchRatio()));
                if (statement.statement() != null) {
                    row.put("lines", coverageLines(statement.statement(), coverage));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * The distinct tables the route's SQL touches under one access, each with a cross-link to its
     * schema table page when introspected (else a plain name). Inferred lexically from every bound
     * statement's 2-way SQL by {@link SqlTableReferences} — a best-effort dependency view, not an
     * execution fact — and ordered case-insensitively for a stable display.
     */
    private static List<Map<String, Object>> tableDependencies(List<RouteSpec.SqlStatement> sql,
            SqlTableReferences.Access access, Map<String, String> tableLinks) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (RouteSpec.SqlStatement statement : sql) {
            if (statement.statement() == null) {
                continue;
            }
            for (SqlTableReferences.TableRef ref : SqlTableReferences
                    .extract(statement.statement())) {
                if (ref.access() == access) {
                    names.add(ref.table());
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("url", tableLinks.get(name.toLowerCase(Locale.ROOT)));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> tests(List<TestRef> tests,
            Map<String, ReportOverlay.CaseResult> resultsByName) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TestRef test : tests) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", test.name());
            row.put("kind", test.kind());
            row.put("target", test.target());
            ReportOverlay.CaseResult result = resultsByName.get(test.name());
            if (result != null) {
                row.put("passed", result.passed());
                row.put("message", result.message());
            }
            rows.add(row);
        }
        return rows;
    }

    /** Adds the per-route overlay status to an index row (no-op without an overlay for the route). */
    private static void applyRouteOverlay(Map<String, Object> row,
            ReportOverlay.RouteReport report) {
        row.put("hasReport", report != null);
        if (report == null) {
            return;
        }
        long passed = report.tests().stream().filter(ReportOverlay.CaseResult::passed).count();
        row.put("covered", report.covered());
        row.put("testsRun", report.tests().size());
        row.put("testsPassed", passed);
        row.put("testsFailed", report.tests().size() - passed);
        row.put("allPassed", report.tests().size() > 0 && passed == report.tests().size());
        row.put("linePct", linePct(report.sql()));
    }

    /** Adds the route page's run summary (covered, pass/fail counts, line/branch coverage). */
    private static void applyRouteSummary(Map<String, Object> model,
            ReportOverlay.RouteReport report) {
        model.put("hasReport", report != null);
        if (report == null) {
            return;
        }
        long passed = report.tests().stream().filter(ReportOverlay.CaseResult::passed).count();
        model.put("covered", report.covered());
        model.put("testsRun", report.tests().size());
        model.put("testsPassed", passed);
        model.put("testsFailed", report.tests().size() - passed);
        model.put("allPassed", report.tests().size() > 0 && passed == report.tests().size());
        model.put("sqlLinePct", linePct(report.sql()));
        model.put("sqlBranchPct", branchPct(report.sql()));
    }

    /** The index coverage-summary strip, or {@code null} when no overlay is present. */
    private static Map<String, Object> reportSummary(ReportOverlay overlay) {
        if (overlay == null || overlay.summary() == null) {
            return null;
        }
        ReportOverlay.Summary summary = overlay.summary();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("runId", overlay.runId());
        model.put("generatedAt", overlay.generatedAt());
        model.put("total", summary.total());
        model.put("passed", summary.passed());
        model.put("failed", summary.failed());
        model.put("allPassed", summary.failed() == 0);
        model.put("sqlLinePct", pct(summary.sqlLineRatio()));
        model.put("sqlBranchPct", pct(summary.sqlBranchRatio()));
        model.put("gatePassed", summary.gatePassed());
        return model;
    }

    private static Map<String, ReportOverlay.CaseResult> resultsByName(
            ReportOverlay.RouteReport report) {
        Map<String, ReportOverlay.CaseResult> byName = new LinkedHashMap<>();
        if (report != null) {
            for (ReportOverlay.CaseResult result : report.tests()) {
                byName.putIfAbsent(result.name(), result);
            }
        }
        return byName;
    }

    /** SQL coverage keyed by file basename, so route-relative spec files join app-relative keys. */
    private static Map<String, ReportOverlay.SqlFileCoverage> coverageByName(
            ReportOverlay.RouteReport report) {
        Map<String, ReportOverlay.SqlFileCoverage> byName = new LinkedHashMap<>();
        if (report != null) {
            for (ReportOverlay.SqlFileCoverage coverage : report.sql()) {
                byName.putIfAbsent(basename(coverage.file()), coverage);
            }
        }
        return byName;
    }

    /** Aggregate covered-of-coverable SQL line percentage, or {@code null} when the route has no SQL. */
    private static Integer linePct(List<ReportOverlay.SqlFileCoverage> sql) {
        if (sql.isEmpty()) {
            return null;
        }
        long coverable = 0;
        long hit = 0;
        for (ReportOverlay.SqlFileCoverage file : sql) {
            coverable += file.coverableLines().size();
            hit += file.coverableLines().stream().filter(file.coveredLines()::contains).count();
        }
        return coverable == 0 ? 100 : (int) Math.round(100.0 * hit / coverable);
    }

    /** Aggregate branch-outcome percentage, or {@code null} when the route's SQL has no branches. */
    private static Integer branchPct(List<ReportOverlay.SqlFileCoverage> sql) {
        long branches = 0;
        long outcomes = 0;
        for (ReportOverlay.SqlFileCoverage file : sql) {
            branches += file.branchCount();
            outcomes += file.branchOutcomes();
        }
        return branches == 0 ? null : (int) Math.round(100.0 * outcomes / (2.0 * branches));
    }

    /**
     * Projects a SQL statement to its source lines tagged by coverage state for line-level
     * highlighting: {@code covered} (emitted at least once), {@code missed} (coverable but never
     * emitted), or {@code plain} (not coverable). The statement text is the full SQL file, so its
     * 1-based line numbers line up with the coverage line sets.
     */
    private static List<Map<String, Object>> coverageLines(String statement,
            ReportOverlay.SqlFileCoverage coverage) {
        Set<Integer> covered = new HashSet<>(coverage.coveredLines());
        Set<Integer> coverable = new HashSet<>(coverage.coverableLines());
        String[] split = statement.split("\n", -1);
        List<String> html = SqlHighlighter.highlightLines(statement);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < split.length; i++) {
            int number = i + 1;
            String state = covered.contains(number)
                    ? "covered"
                    : coverable.contains(number) ? "missed" : "plain";
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("number", number);
            line.put("text", split[i]);
            line.put("html", html.get(i));
            line.put("state", state);
            lines.add(line);
        }
        return lines;
    }

    /**
     * The run-trend model from the bounded history: pass-rate, SQL line, and SQL branch sparkline
     * series (comma-separated ratios for {@code hc-sparkline data-values}) plus their latest
     * percentages. {@code null} when there are fewer than two runs (a single point is not a trend).
     */
    private static Map<String, Object> trend(List<DocService.HistoryPoint> history) {
        if (history == null || history.size() < 2) {
            return null;
        }
        List<Double> pass = new ArrayList<>();
        List<Double> line = new ArrayList<>();
        List<Double> branch = new ArrayList<>();
        for (DocService.HistoryPoint point : history) {
            pass.add(point.total() > 0 ? (double) point.passed() / point.total() : 1.0);
            line.add(point.sqlLineRatio());
            branch.add(point.sqlBranchRatio());
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("runs", history.size());
        // The retained span (oldest -> newest run date) conveys the trend's depth (backlog F9): with
        // the history cap relaxed it can cover far more than the former 20 runs.
        model.put("from", runDate(history.get(0)));
        model.put("to", runDate(history.get(history.size() - 1)));
        model.put("passSpark", spark(pass));
        model.put("passPct", pct(pass.get(pass.size() - 1)));
        model.put("lineSpark", spark(line));
        model.put("linePct", pct(line.get(line.size() - 1)));
        model.put("branchSpark", spark(branch));
        model.put("branchPct", pct(branch.get(branch.size() - 1)));
        return model;
    }

    /** The calendar date of a run (the date part of its ISO instant), or {@code null} when unknown. */
    private static String runDate(DocService.HistoryPoint point) {
        String at = point.generatedAt();
        if (at == null || at.isBlank()) {
            return null;
        }
        int t = at.indexOf('T');
        return t < 0 ? at : at.substring(0, t);
    }

    /**
     * A comma-separated raw-ratio series for an {@code hc-sparkline data-values} attribute. Each
     * value is clamped to {@code [0, 1]} and trimmed to at most four decimals; the kit's
     * {@code installSparkline} behavior draws the inline SVG, scaled to the pinned 0..1 domain.
     */
    private static String spark(List<Double> values) {
        StringBuilder series = new StringBuilder();
        for (double value : values) {
            double ratio = Math.max(0.0, Math.min(1.0, value));
            if (series.length() > 0) {
                series.append(',');
            }
            series.append(java.math.BigDecimal.valueOf(ratio)
                    .setScale(4, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString());
        }
        return series.toString();
    }

    private static int pct(double ratio) {
        return (int) Math.round(ratio * 100);
    }

    private static String basename(String file) {
        if (file == null) {
            return null;
        }
        int slash = file.lastIndexOf('/');
        return slash < 0 ? file : file.substring(slash + 1);
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
